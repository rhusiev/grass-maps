package nl.r1a.grassmap.plugins.nav.simulator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nl.r1a.grassmap.navigation.GeometryRouteFollower
import nl.r1a.grassmap.navigation.NavigationConfig
import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.navigation.NavigationRun
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
 * The simulated navigation run (T-0315).
 *
 * One synthetic [LocationFix] is placed every [cadenceMillis] of simulation time
 * [speedMetersPerSecond] further along the route, so the fixes move at exactly
 * 5 km/h whatever the vertex spacing is. They are fed through the production
 * [NavigationSession] and the production [GeometryRouteFollower]; the session's
 * own arrival check ends the run, and the simulator never sets a phase itself.
 *
 * The clock is [SimulationClock], advanced only when a fix is produced. A JVM
 * test therefore runs the whole route without sleeping, while a future
 * navigation screen can use the status flow to show the run.
 */
class NavigationSimulator(
    private val scope: CoroutineScope,
    private val routing: RoutingEngine = NoRerouteEngine,
    private val config: NavigationConfig = NavigationConfig(),
    private val followerFactory: () -> RouteFollowerEngine = { GeometryRouteFollower(config) },
    private val clock: SimulationClock = SimulationClock(),
    private val cadenceMillis: Long = NavSimulatorContract.CADENCE_MILLIS,
    private val speedMetersPerSecond: Double = NavSimulatorContract.SPEED_METERS_PER_SECOND,
    private val log: (String) -> Unit = {},
) {
    init {
        require(cadenceMillis > 0) { "the cadence must be positive" }
        require(speedMetersPerSecond > 0.0) { "the speed must be positive" }
    }

    private val speedKmh = speedMetersPerSecond * SECONDS_PER_HOUR / METERS_PER_KILOMETER
    private val stepMeters = speedMetersPerSecond * cadenceMillis / MILLIS_PER_SECOND

    private val mutableStatus =
        MutableStateFlow(
            SimulatorStatus(
                speedKmh = speedKmh,
                cadenceMillis = cadenceMillis,
            ),
        )

    /** The run's current state, also published as `nav.simulator.state`. */
    val status: StateFlow<SimulatorStatus> = mutableStatus.asStateFlow()

    private val mutableFixes = MutableSharedFlow<LocationFix>(extraBufferCapacity = FIX_BUFFER)

    /** The fixes this simulator produced, for a screen or a test that samples them. */
    val fixes: SharedFlow<LocationFix> = mutableFixes.asSharedFlow()

    private var sessionState: StateFlow<NavigationState>? = null

    /** The latest session state; null before the first [start]. */
    val navigation: StateFlow<NavigationState>?
        get() = sessionState

    private var activeJob: Job? = null
    private var activeChannel: Channel<LocationFix>? = null
    private var activeStop: CompletableDeferred<Unit>? = null

    /**
     * Starts a run on [route]. A second start while one is running changes
     * nothing and returns the current status. Invalid or empty geometry is
     * invalid input.
     */
    suspend fun start(
        route: Route,
        profile: RouteProfile = RouteProfile.Walk,
    ): Outcome<SimulatorStatus> {
        if (activeJob?.isActive == true) {
            log("nav-simulator-start-ignored phase=${mutableStatus.value.phase}")
            return Outcome.Success(mutableStatus.value)
        }
        if (route.geometry.size < MIN_ROUTE_POINTS ||
            !route.distanceMeters.isFinite() ||
            route.distanceMeters <= 0.0
        ) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val walk = RouteWalk(route.geometry)
        if (walk.totalMeters <= 0.0) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        clock.reset()
        val channel = Channel<LocationFix>(capacity = CHANNEL_CAPACITY)
        val stopSignal = CompletableDeferred<Unit>()
        val session =
            NavigationSession(
                follower = followerFactory(),
                routing = routing,
                profile = profile,
                config = config,
                clockMillis = { clock.nowMillis },
                log = log,
            )
        sessionState = session.state
        activeChannel = channel
        activeStop = stopSignal
        mutableStatus.value =
            SimulatorStatus(
                running = true,
                speedKmh = speedKmh,
                cadenceMillis = cadenceMillis,
            )
        val firstState = CompletableDeferred<NavigationState?>()
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                var outcome: Outcome<NavigationRun>? = null
                val driver = launch { drive(session, walk, channel, stopSignal, firstState) }
                try {
                    outcome = session.run(route, channel.receiveAsFlow())
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    log("nav-simulator-session-failed error=${error.javaClass.simpleName}: ${error.message}")
                    outcome = Outcome.Failure(AppError.Internal(SESSION_ID, error.message))
                } finally {
                    withContext(NonCancellable) {
                        driver.cancelAndJoin()
                        channel.close()
                        if (!firstState.isCompleted) {
                            firstState.complete(null)
                        }
                        // A completed run may be replaced by a new start before
                        // this block runs; only the current run may publish.
                        if (activeChannel === channel) {
                            finish(outcome)
                            activeJob = null
                            activeChannel = null
                            activeStop = null
                        }
                    }
                }
            }
        activeJob = job
        job.start()
        log(
            "nav-simulator-start points=${route.geometry.size} distance_m=${format(route.distanceMeters)} " +
                "speed_kmh=${format(speedKmh)} cadence_ms=$cadenceMillis profile=$profile",
        )
        val first = withTimeoutOrNull(START_TIMEOUT_MILLIS) { firstState.await() }
        if (first == null) {
            job.cancelAndJoin()
            return Outcome.Failure(AppError.NotReady)
        }
        return Outcome.Success(mutableStatus.value)
    }

    /**
     * Stops the run by ending the fix flow. The session then publishes
     * [NavigationPhase.Stopped] itself, so a stop is not an arrival.
     */
    suspend fun stop(): SimulatorStatus {
        val job = activeJob
        if (job == null) {
            if (mutableStatus.value.running) {
                mutableStatus.value =
                    mutableStatus.value.copy(
                        phase = SimulatorPhase.Stopped,
                        arrived = false,
                        running = false,
                    )
            }
            return mutableStatus.value
        }
        activeStop?.complete(Unit)
        activeChannel?.close()
        job.join()
        return mutableStatus.value
    }

    /** Waits for the current run to end, then returns its final status. */
    suspend fun awaitFinished(): SimulatorStatus {
        activeJob?.join()
        return mutableStatus.value
    }

    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun drive(
        session: NavigationSession,
        walk: RouteWalk,
        channel: Channel<LocationFix>,
        stopSignal: CompletableDeferred<Unit>,
        firstState: CompletableDeferred<NavigationState?>,
    ) {
        var along = 0.0
        var count = 0
        try {
            while (currentCoroutineContext().isActive) {
                if (channel.isClosedForSend) {
                    break
                }
                val fix =
                    LocationFix(
                        position = walk.pointAt(along),
                        accuracyMeters = FIX_ACCURACY_METERS,
                        bearingDegrees = walk.bearingAt(along),
                        speedMetersPerSecond = speedMetersPerSecond,
                        timeMillis = clock.nowMillis,
                    )
                try {
                    channel.send(fix)
                } catch (closed: ClosedSendChannelException) {
                    break
                }
                mutableFixes.emit(fix)
                count += 1
                val state = session.state.first { it.fix == fix }
                mutableStatus.value = statusOf(state, count)
                if (!firstState.isCompleted) {
                    firstState.complete(state)
                }
                if (state.phase == NavigationPhase.Arrived) {
                    break
                }
                if (channel.isClosedForSend || along >= walk.totalMeters) {
                    break
                }
                val stopped = withTimeoutOrNull(cadenceMillis) { stopSignal.await() }
                if (stopped != null) {
                    break
                }
                clock.advance(cadenceMillis)
                along = (along + stepMeters).coerceAtMost(walk.totalMeters)
            }
        } finally {
            channel.close()
            if (!firstState.isCompleted) {
                firstState.complete(null)
            }
        }
    }

    private fun statusOf(
        state: NavigationState,
        count: Int,
    ): SimulatorStatus =
        SimulatorStatus(
            phase = SimulatorPhase.from(state.phase),
            fixCount = count,
            arrived = state.phase == NavigationPhase.Arrived,
            running = true,
            remainingDistanceMeters = state.remainingDistanceMeters,
            lastFixTimeMillis = state.fix?.timeMillis,
            speedKmh = speedKmh,
            cadenceMillis = cadenceMillis,
        )

    private fun finish(outcome: Outcome<NavigationRun>?) {
        val state = sessionState?.value
        val run = (outcome as? Outcome.Success)?.value
        val arrived = run?.arrived == true || state?.phase == NavigationPhase.Arrived
        val phase =
            when {
                arrived -> SimulatorPhase.Arrived
                outcome == null -> SimulatorPhase.Stopped
                outcome is Outcome.Failure -> SimulatorPhase.Stopped
                state != null -> SimulatorPhase.from(state.phase)
                else -> SimulatorPhase.Stopped
            }
        mutableStatus.value =
            mutableStatus.value.copy(
                phase = if (phase == SimulatorPhase.Idle) SimulatorPhase.Stopped else phase,
                fixCount = run?.fixCount ?: mutableStatus.value.fixCount,
                arrived = arrived,
                running = false,
                remainingDistanceMeters = state?.remainingDistanceMeters ?: mutableStatus.value.remainingDistanceMeters,
                lastFixTimeMillis = state?.fix?.timeMillis ?: mutableStatus.value.lastFixTimeMillis,
            )
        log(
            "nav-simulator-done phase=${mutableStatus.value.phase} fixes=${mutableStatus.value.fixCount} " +
                "arrived=$arrived remaining_m=${format(mutableStatus.value.remainingDistanceMeters)}",
        )
    }

    private fun format(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    private companion object {
        const val SESSION_ID = "nav.simulator"
        const val MIN_ROUTE_POINTS = 2
        const val CHANNEL_CAPACITY = 1
        const val FIX_BUFFER = 16
        const val FIX_ACCURACY_METERS = 5.0
        const val START_TIMEOUT_MILLIS = 5_000L
        const val MILLIS_PER_SECOND = 1_000.0
        const val SECONDS_PER_HOUR = 3_600.0
        const val METERS_PER_KILOMETER = 1_000.0
    }
}

/**
 * The simulated fixes stay on the route, so a reroute is unreachable. The
 * session still requires an engine; this one refuses rather than inventing a
 * replacement line.
 */
internal object NoRerouteEngine : RoutingEngine {
    override val contract: ProviderContract = RoutingEngineContract.REFERENCE

    override fun capabilities(): RoutingCapabilities =
        RoutingCapabilities(
            profiles = RouteProfile.entries.toSet(),
            modifierKinds = emptySet(),
            maxAlternatives = 0,
            maxDistanceMeters = MAX_DISTANCE_METERS,
        )

    override suspend fun route(
        request: RouteRequest,
        cancellation: CancellationHandle,
    ): Outcome<List<Route>> = Outcome.Failure(AppError.Unsupported)

    private const val MAX_DISTANCE_METERS = 250_000.0
}
