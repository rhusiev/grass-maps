package nl.r1a.grassmap.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import nl.r1a.grassmap.navigation.FerrostarRouteFollower
import nl.r1a.grassmap.navigation.NavigationConfig
import nl.r1a.grassmap.navigation.NavigationControl
import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.navigation.NavigationSession
import nl.r1a.grassmap.navigation.NavigationState
import nl.r1a.grassmap.navigation.RouteFollowerEngine
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.provider.ProviderContract
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.provider.RoutingCapabilities
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine
import nl.r1a.grassmap.plugin.api.provider.RoutingEngineContract
import java.util.Locale

/**
 * The one live navigation session, its foreground service and its pause state.
 *
 * A session is started with a route and a profile. It runs on [scope] while the
 * process lives; the navigation foreground service keeps the process and
 * location alive when the activity leaves the foreground. A session ends on
 * arrival, on a fix-flow failure or on [stop]. Arrival ends the location
 * session but keeps the notification's Stop action until [dismiss] or the
 * notification's own Stop; every other ending stops and removes the service,
 * which is what releases location when the activity is gone.
 *
 * Start is idempotent: a second start while a session exists changes nothing
 * and reports that no new session began. Stop is idempotent too, and stopping
 * without a session changes nothing.
 *
 * Reroute needs the routing engine. When no engine is available the session
 * starts with reroute off rather than failing: guidance still works on the
 * route it was given.
 */
class NavigationController(
    private val scope: CoroutineScope,
    private val fixes: Flow<LocationFix?>,
    private val hasLocationPermission: () -> Boolean,
    private val requiresLocationPermission: () -> Boolean,
    private val routing: suspend () -> RoutingEngine?,
    private val service: NavigationServiceHandle,
    private val followerFactory: () -> RouteFollowerEngine = { FerrostarRouteFollower() },
    private val config: NavigationConfig = NavigationConfig(),
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val log: (String) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(NavigationState())

    /** The latest published state; [NavigationPhase.Idle] before the first session. */
    val state: StateFlow<NavigationState> = mutableState.asStateFlow()

    private val lock = Any()
    private var job: Job? = null
    private var control: NavigationControl? = null

    /** Whether a session is running or still stopping. */
    val isActive: Boolean
        get() = synchronized(lock) { job != null }

    /**
     * Starts guidance on [route]. Returns true when this call started a
     * session, false when one already existed. A missing location permission,
     * or a system that refuses the foreground service, is a typed failure and
     * starts nothing.
     *
     * [reroute] is false for a followed track (T-0412). Its line is the
     * guidance the user asked for, so replacing it with a road route computed
     * from the current position would abandon the track; while the user is
     * still walking to the join point the session then reports off route
     * instead of rerouting.
     */
    fun start(
        route: Route,
        profile: RouteProfile,
        reroute: Boolean = true,
    ): Outcome<Boolean> {
        synchronized(lock) {
            if (job != null) {
                log("navigation-start-ignored phase=${mutableState.value.phase}")
                return Outcome.Success(false)
            }
            if (route.geometry.size < MIN_ROUTE_POINTS) {
                return Outcome.Failure(AppError.InvalidInput)
            }
            if (requiresLocationPermission() && !hasLocationPermission()) {
                log("navigation-start-refused reason=permission")
                return Outcome.Failure(AppError.PermissionDenied)
            }
            try {
                service.start()
            } catch (error: IllegalStateException) {
                log("navigation-start-refused reason=service error=${error.javaClass.simpleName}")
                return Outcome.Failure(AppError.NotReady)
            } catch (error: SecurityException) {
                log("navigation-start-refused reason=permission detail=foreground-service")
                return Outcome.Failure(AppError.PermissionDenied)
            }
            mutableState.value =
                NavigationState(
                    phase = NavigationPhase.Following,
                    activeRoute = route,
                    remainingDistanceMeters = route.distanceMeters,
                    remainingDurationSeconds = route.durationSeconds,
                    profile = profile,
                )
            val started =
                scope.launch(start = CoroutineStart.LAZY) {
                    runSession(route, profile, reroute)
                }
            job = started
            started.invokeOnCompletion { finish(started) }
            started.start()
            log(
                "navigation-start points=${route.geometry.size} " +
                    "distance_m=${format(route.distanceMeters)} profile=$profile",
            )
            return Outcome.Success(true)
        }
    }

    /** Pauses guidance. Returns whether the session was paused by this call. */
    fun pause(): Boolean {
        val active = synchronized(lock) { if (job == null) null else control }
        if (active == null) {
            log("navigation-pause-ignored")
            return false
        }
        val changed = active.pause()
        if (changed) {
            publishPhase(NavigationPhase.Paused)
        }
        return changed
    }

    /** Resumes guidance. Returns whether the session was resumed by this call. */
    fun resume(): Boolean {
        val active = synchronized(lock) { if (job == null) null else control }
        if (active == null) {
            log("navigation-resume-ignored")
            return false
        }
        val changed = active.resume()
        if (changed) {
            publishPhase(NavigationPhase.Following)
        }
        return changed
    }

    /** Ends the session. Returns false when there was no session to stop. */
    fun stop(): Boolean {
        val active = synchronized(lock) { job } ?: return false
        active.cancel()
        return true
    }

    /**
     * Clears a finished trip's last state so the navigation screen closes on
     * Done. A running session is left alone; End and Stop own that path.
     */
    fun dismiss() {
        synchronized(lock) {
            if (job != null) {
                return
            }
            val current = mutableState.value
            if (current.phase == NavigationPhase.Arrived || current.phase == NavigationPhase.Stopped) {
                mutableState.value = NavigationState()
                service.stop()
            }
        }
    }

    /**
     * Publishes a phase change at once, so pause and resume reach the
     * notification in the same turn instead of waiting for the next fix.
     */
    private fun publishPhase(phase: NavigationPhase) {
        val current = mutableState.value
        if (current.phase == NavigationPhase.Arrived || current.phase == NavigationPhase.Stopped) {
            return
        }
        mutableState.value = current.copy(phase = phase)
    }

    private suspend fun runSession(
        route: Route,
        profile: RouteProfile,
        reroute: Boolean,
    ) {
        val sessionControl = NavigationControl()
        synchronized(lock) {
            if (job == null) {
                // The session was cancelled before its body began.
                return
            }
            control = sessionControl
        }
        // Resolving the engine can activate a plugin, so pause works while it
        // runs: the flag is set now and the session publishes Paused when its
        // watcher starts.
        val engine =
            try {
                routing()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                log("navigation-engine-failed error=${error.javaClass.simpleName}: ${error.message}")
                null
            }
        val session =
            NavigationSession(
                follower = followerFactory(),
                routing = engine ?: NoRerouteEngine,
                profile = profile,
                config = if (engine == null || !reroute) config.copy(reroute = false) else config,
                clockMillis = clockMillis,
                log = log,
                control = sessionControl,
            )
        log("navigation-session profile=$profile reroute=${engine != null && reroute}")
        try {
            coroutineScope {
                val mirror =
                    launch {
                        session.state.collect { state ->
                            // The session starts at Idle until its first fix; the
                            // controller already published the starting state.
                            if (state.phase != NavigationPhase.Idle) {
                                mutableState.value = state
                            }
                        }
                    }
                try {
                    when (val outcome = session.run(route, fixes.filterNotNull())) {
                        is Outcome.Failure -> {
                            log("navigation-failed error=${outcome.error}")
                        }

                        is Outcome.Success -> {
                            log(
                                "navigation-finished fixes=${outcome.value.fixCount} " +
                                    "inaccurate_fixes=${outcome.value.inaccurateFixCount} " +
                                    "reroutes=${outcome.value.rerouteCount} " +
                                    "arrived=${outcome.value.arrived}",
                            )
                        }
                    }
                } finally {
                    // The final publication happens just before the session
                    // returns; copy it before the mirror stops so an arrival is
                    // not lost to the cancellation race.
                    mutableState.value = session.state.value
                    mirror.cancel()
                }
            }
        } catch (cancellation: CancellationException) {
            log("navigation-cancelled")
            throw cancellation
        } catch (error: Exception) {
            log("navigation-session-failed error=${error.javaClass.simpleName}: ${error.message}")
        }
    }

    /**
     * The single teardown point: it runs when the session body completes and
     * also when a session is cancelled before its body began. It publishes the
     * terminal phase, stops the service and only then clears the job, so a
     * start that follows cannot race the stop into leaving a stale service.
     */
    private fun finish(completed: Job) {
        synchronized(lock) {
            if (job !== completed) {
                return
            }
            control = null
            val current = mutableState.value
            if (current.phase == NavigationPhase.Arrived) {
                // The arrival notification keeps its Stop action until the user
                // dismisses it; the location session itself has ended.
                log("navigation-arrival-notification-kept")
            } else {
                mutableState.value = current.copy(phase = NavigationPhase.Stopped)
                service.stop()
                log("navigation-service-stop phase=${mutableState.value.phase}")
            }
            job = null
        }
    }

    private fun format(value: Double): String = if (value.isFinite()) String.format(Locale.ROOT, "%.1f", value) else "-"

    /** The engine used when no routing pack can answer; reroute is off then. */
    private object NoRerouteEngine : RoutingEngine {
        override val contract: ProviderContract = RoutingEngineContract.REFERENCE

        override fun capabilities(): RoutingCapabilities =
            RoutingCapabilities(
                profiles = RouteProfile.entries.toSet(),
                modifierKinds = emptySet(),
                maxAlternatives = 0,
                maxDistanceMeters = 0.0,
            )

        override suspend fun route(
            request: RouteRequest,
            cancellation: CancellationHandle,
        ): Outcome<List<Route>> =
            Outcome.Failure(
                AppError.RoutingPackUnavailable(AppError.RoutingPackUnavailable.Reason.Absent),
            )
    }

    private companion object {
        const val MIN_ROUTE_POINTS = 2
    }
}
