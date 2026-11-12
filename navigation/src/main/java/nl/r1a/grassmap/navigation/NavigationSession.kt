package nl.r1a.grassmap.navigation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteProgress
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine
import java.util.Locale
import kotlin.math.roundToLong

/**
 * The navigation seam: one active route, a stream of fixes, an engine-neutral
 * state, and a reroute through [RoutingEngine] when the route is left.
 *
 * Guidance numbers come from one place. The follower reports where the driver
 * is on the line; [RouteProgress] turns that position into remaining distance,
 * remaining time and the arrival time from the route's own per-maneuver and
 * per-leg model. The follower's own duration is never published, because the
 * core prorates its steps by distance and that would average a slow part of the
 * route into a fast one.
 *
 * Off-route uses Ferrostar's verdict when the core gives one, because the core
 * owns `StaticThreshold` with the same [NavigationConfig.maxDeviationMeters]
 * that the geometry floor applies. The floor decides when the core reports no
 * verdict. A fix must be off route for [NavigationConfig.offRouteConfirmFixes]
 * in a row, and must then travel the profile's reroute budget
 * ([NavigationConfig.travelMeters]) before the session asks for a new route.
 * The two conditions are both necessary: confirmation says the reading is not a
 * one-fix glitch, the budget says the driver has actually left rather than
 * clipped a corner.
 *
 * The reroute is speculative: the request is made while the current line is
 * still the published one, and the rerouted line is announced only when the
 * engine has returned it. A fix worse than
 * [NavigationConfig.minimumHorizontalAccuracyMeters] is ignored entirely, so it
 * can neither start nor cancel a reroute and the last good fix stays on screen.
 * One request is in flight at a time, and every attempt resets the budget, so a
 * burst of off-route fixes cannot queue a storm of requests.
 *
 * Pause and resume go through [control]. While paused the session publishes
 * [NavigationPhase.Paused] and drops every fix before the follower and the
 * gate, so guidance stands still and no reroute can start. Resuming continues
 * from the next fix; no fix from the pause is replayed.
 */
class NavigationSession(
    private val follower: RouteFollowerEngine,
    private val routing: RoutingEngine,
    private val profile: RouteProfile,
    private val config: NavigationConfig = NavigationConfig(),
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val log: (String) -> Unit = {},
    val control: NavigationControl = NavigationControl(),
    private val onReroute: (RerouteEvent) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(NavigationState())

    /** The engine that needs no native core; used when [follower] cannot start. */
    private val fallback = GeometryRouteFollower(config, log)

    /** The latest state; a consumer draws [NavigationState.activeRoute] on a revision change. */
    val state: StateFlow<NavigationState> = mutableState.asStateFlow()

    /**
     * Follows [initialRoute] over [fixes] until the flow ends or the trip
     * arrives. Bad geometry is invalid input; a missing follower degrades to
     * the geometry fallback instead of failing the run.
     */
    suspend fun run(
        initialRoute: Route,
        fixes: Flow<LocationFix>,
    ): Outcome<NavigationRun> {
        if (initialRoute.geometry.size < MIN_ROUTE_POINTS) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        var activeRoute = initialRoute
        var locator = PolylineLocator(activeRoute.geometry)
        var progress = RouteProgress.of(activeRoute)
        log(
            "navigation-model source=${progress.source} spans=${progress.spanCount} " +
                "total_m=${format(progress.totalDistanceMeters)} total_s=${format(activeRoute.durationSeconds)} " +
                "gate_m=${format(config.travelMeters(profile))} gate_s=${format(config.offRouteDelaySeconds)}",
        )
        var active =
            try {
                open(activeRoute)
            } catch (error: Exception) {
                return Outcome.Failure(AppError.Internal(SESSION_ID, error.message))
            }
        var revision = 0L
        var rerouteCount = 0
        var fixCount = 0
        var inaccurateFixCount = 0
        var offRouteFixCount = 0
        var offRouteStreak = 0
        var offRouteStartFix = 0
        var detectionMillis: Long? = null
        var lastRerouteMillis: Long? = null
        var arrived = false
        var previousRelation = RouteRelation.Unknown
        var previousFix: LocationFix? = null
        var inaccurate = false
        var rerouteInFlight = false
        val rerouteGate = RerouteGate(profile, config)
        val sessionStartedMillis = clockMillis()

        try {
            coroutineScope {
                val pauseWatcher =
                    launch {
                        control.paused.collect { paused ->
                            val current = mutableState.value
                            // Arrival and the watcher cancel are not atomic: a
                            // pause between them must not replace the terminal phase.
                            if (paused && current.phase != NavigationPhase.Paused && !isTerminal(current.phase)) {
                                log(
                                    "navigation-paused fixes=$fixCount " +
                                        "remaining_m=${format(current.remainingDistanceMeters)}",
                                )
                                publish(current.copy(phase = NavigationPhase.Paused))
                            } else if (!paused && current.phase == NavigationPhase.Paused) {
                                log("navigation-resumed fixes=$fixCount")
                                publish(current.copy(phase = NavigationPhase.Following))
                            }
                        }
                    }
                try {
                    fixes
                        .takeWhile { !arrived }
                        .filter { !control.paused.value }
                        .collect { fix ->
                            fixCount += 1
                            if (!isAccurate(fix)) {
                                inaccurateFixCount += 1
                                if (!inaccurate) {
                                    inaccurate = true
                                    log(
                                        "navigation-fix-ignored fixes=$fixCount " +
                                            "accuracy_m=${format(fix.accuracyMeters)} " +
                                            "limit_m=${config.minimumHorizontalAccuracyMeters}",
                                    )
                                }
                                return@collect
                            }
                            if (inaccurate) {
                                inaccurate = false
                                log("navigation-fix-accepted fixes=$fixCount accuracy_m=${format(fix.accuracyMeters)}")
                            }
                            var projection = locator.locate(fix.position)
                            var snapshot = read(active, fix)

                            val candidateOffRoute = isOffRoute(snapshot, projection?.distanceMeters)
                            if (candidateOffRoute && offRouteStreak == 0) {
                                offRouteStartFix = fixCount
                            }
                            offRouteStreak = if (candidateOffRoute) offRouteStreak + 1 else 0
                            if (candidateOffRoute) {
                                offRouteFixCount += 1
                                rerouteGate.observe(previousFix, fix)
                            } else if (rerouteGate.hasPending) {
                                log(
                                    "navigation-reroute-cancelled fixes=$fixCount " +
                                        "travel_m=${format(rerouteGate.travelledMeters)}",
                                )
                                rerouteGate.reset()
                            }
                            previousFix = fix

                            val confirmed = candidateOffRoute && offRouteStreak >= config.offRouteConfirmFixes
                            if (confirmed && detectionMillis == null) {
                                detectionMillis = clockMillis() - sessionStartedMillis
                                log(
                                    "navigation-off-route fixes=$fixCount " +
                                        "after_ms=$detectionMillis " +
                                        "distance_m=${format(projection?.distanceMeters)} " +
                                        "deviation=${snapshot?.deviation}",
                                )
                            }
                            val reroute = confirmed && config.reroute && rerouteGate.isMet && !rerouteInFlight
                            if (reroute) {
                                rerouteInFlight = true
                                try {
                                    val along = alongMeters(progress, locator, snapshot, projection)
                                    val request =
                                        RouteRequest(
                                            waypoints = listOf(fix.position) + remainingWaypoints(activeRoute, along),
                                            profile = profile,
                                        )
                                    val started = clockMillis()
                                    when (val outcome = routing.route(request, CancellationHandle.None)) {
                                        is Outcome.Failure -> {
                                            log("navigation-reroute-failed fixes=$fixCount error=${outcome.error}")
                                            rerouteGate.reset()
                                        }

                                        is Outcome.Success -> {
                                            val replacement = outcome.value.firstOrNull { it.geometry.size >= MIN_ROUTE_POINTS }
                                            if (replacement == null) {
                                                log("navigation-reroute-failed fixes=$fixCount error=${AppError.NotFound}")
                                                rerouteGate.reset()
                                            } else {
                                                val budgetTravelMeters = rerouteGate.travelledMeters
                                                val eventEpisodeStartFix = offRouteStartFix
                                                active.close()
                                                activeRoute = replacement
                                                locator = PolylineLocator(replacement.geometry)
                                                progress = RouteProgress.of(replacement)
                                                revision += 1
                                                rerouteCount += 1
                                                rerouteGate.reset()
                                                active = open(replacement)
                                                val durationMillis = clockMillis() - started
                                                lastRerouteMillis = durationMillis
                                                projection = locator.locate(fix.position)
                                                snapshot = read(active, fix)
                                                // The replacement line starts where Valhalla
                                                // snapped the off-route fix, so the fix can
                                                // still be off the new line. Do not report
                                                // on-route until the geometry says so.
                                                offRouteStreak =
                                                    if (isOffRoute(snapshot, projection?.distanceMeters)) 1 else 0
                                                if (offRouteStreak == 1) {
                                                    // Still off the replacement: a new episode
                                                    // starts here, not at the original one.
                                                    offRouteStartFix = fixCount
                                                }
                                                previousRelation = relationOf(offRouteStreak)
                                                log(
                                                    "navigation-reroute fixes=$fixCount revision=$revision " +
                                                        "duration_ms=$durationMillis " +
                                                        "episode_fix=$eventEpisodeStartFix budget_m=${format(budgetTravelMeters)} " +
                                                        "distance_m=${format(replacement.distanceMeters)} " +
                                                        "model=${progress.source} spans=${progress.spanCount} " +
                                                        "waypoints=${request.waypoints.size} " +
                                                        "destination=${format(request.waypoints.last().latitude)}," +
                                                        "${format(request.waypoints.last().longitude)}",
                                                )
                                                onReroute(
                                                    RerouteEvent(
                                                        index = rerouteCount,
                                                        episodeStartFix = eventEpisodeStartFix,
                                                        budgetFix = fixCount,
                                                        installedFix = fixCount,
                                                        budgetTravelMeters = budgetTravelMeters,
                                                        durationMillis = durationMillis,
                                                        replacementDistanceMeters = replacement.distanceMeters,
                                                        replacementPoints = replacement.geometry.size,
                                                    ),
                                                )
                                                // Announce the new line only now: the state
                                                // carries it together with the phase, so a
                                                // consumer never shows a reroute that has
                                                // not produced a route.
                                                publish(
                                                    stateOf(
                                                        phase = NavigationPhase.Rerouting,
                                                        fix = fix,
                                                        snapshot = snapshot,
                                                        projection = projection,
                                                        relation = previousRelation,
                                                        route = activeRoute,
                                                        progress = progress,
                                                        locator = locator,
                                                        revision = revision,
                                                        rerouteCount = rerouteCount,
                                                        lastRerouteMillis = lastRerouteMillis,
                                                    ),
                                                )
                                                return@collect
                                            }
                                        }
                                    }
                                } finally {
                                    rerouteInFlight = false
                                }
                            }

                            val relation = relationOf(offRouteStreak)
                            if (relation == RouteRelation.OnRoute && previousRelation != RouteRelation.OnRoute) {
                                log(
                                    "navigation-on-route fixes=$fixCount " +
                                        "after_ms=${clockMillis() - sessionStartedMillis} " +
                                        "distance_m=${format(projection?.distanceMeters)}",
                                )
                            }
                            previousRelation = relation
                            arrived = arrivalOf(snapshot, projection)
                            publish(
                                stateOf(
                                    phase = if (arrived) NavigationPhase.Arrived else NavigationPhase.Following,
                                    fix = fix,
                                    snapshot = snapshot,
                                    projection = projection,
                                    relation = relation,
                                    route = activeRoute,
                                    progress = progress,
                                    locator = locator,
                                    revision = revision,
                                    rerouteCount = rerouteCount,
                                    lastRerouteMillis = lastRerouteMillis,
                                ),
                            )
                            if (arrived) {
                                log(
                                    "navigation-arrived fixes=$fixCount " +
                                        "remaining_m=${format(mutableState.value.remainingDistanceMeters)}",
                                )
                            }
                        }
                } finally {
                    pauseWatcher.cancel()
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log("navigation-session-failed error=${error.javaClass.simpleName}: ${error.message}")
            publish(
                mutableState.value.copy(
                    phase = NavigationPhase.Stopped,
                    relation = mutableState.value.relation,
                    activeRoute = activeRoute,
                    routeRevision = revision,
                    rerouteCount = rerouteCount,
                    lastRerouteMillis = lastRerouteMillis,
                ),
            )
            return Outcome.Failure(AppError.Internal(SESSION_ID, error.message))
        } finally {
            active.close()
        }

        publish(
            mutableState.value.copy(
                phase = if (arrived) NavigationPhase.Arrived else NavigationPhase.Stopped,
                activeRoute = activeRoute,
                routeRevision = revision,
                rerouteCount = rerouteCount,
                lastRerouteMillis = lastRerouteMillis,
            ),
        )
        return Outcome.Success(
            NavigationRun(
                fixCount = fixCount,
                inaccurateFixCount = inaccurateFixCount,
                offRouteFixCount = offRouteFixCount,
                rerouteCount = rerouteCount,
                arrived = arrived,
                detectionMillis = detectionMillis,
                lastRerouteMillis = lastRerouteMillis,
                finalRoute = activeRoute,
            ),
        )
    }

    /**
     * A fix worse than the configured horizontal accuracy is not navigation
     * input: it is dropped before the follower, the off-route streak, the gate
     * and the published state, so a bad reading cannot move the marker, start a
     * reroute or cancel one that the last good fix armed.
     */
    private fun isAccurate(fix: LocationFix): Boolean {
        val accuracy = fix.accuracyMeters
        return accuracy.isFinite() && accuracy > 0.0 && accuracy <= config.minimumHorizontalAccuracyMeters
    }

    /**
     * The waypoints still ahead: each remaining leg's endpoint, ending with the
     * destination. A reroute continues the plan instead of replacing it, so a
     * trip with intermediate stops keeps visiting the ones not yet reached.
     * A route with no legs is a single leg to its end.
     */
    private fun remainingWaypoints(
        route: Route,
        alongMeters: Double,
    ): List<GeoPoint> {
        val destination = route.geometry.lastOrNull() ?: return emptyList()
        if (route.segments.size < 2) {
            return listOf(destination)
        }
        val result = mutableListOf<GeoPoint>()
        var cumulative = 0.0
        route.segments.forEachIndexed { index, segment ->
            if (!segment.distanceMeters.isFinite() || segment.endIndex !in 1..route.geometry.size) {
                return@forEachIndexed
            }
            cumulative += segment.distanceMeters
            val endpoint = route.geometry[segment.endIndex - 1]
            val passed = index < route.segments.lastIndex && cumulative <= alongMeters + LEG_PASSED_EPSILON_METERS
            if (!passed && endpoint != result.lastOrNull()) {
                result += endpoint
            }
        }
        if (result.lastOrNull() != destination) {
            result += destination
        }
        return result
    }

    private fun open(route: Route): ActiveFollower {
        when (val primary = follower.begin(route)) {
            is Outcome.Success -> {
                return ActiveFollower(primary.value)
            }

            is Outcome.Failure -> {
                log("navigation-follower-unavailable error=${primary.error} fallback=geometry")
            }
        }
        return when (val geometry = fallback.begin(route)) {
            is Outcome.Success -> {
                ActiveFollower(geometry.value)
            }

            is Outcome.Failure -> {
                log("navigation-geometry-follower-unavailable error=${geometry.error}")
                ActiveFollower(handle = null)
            }
        }
    }

    private fun read(
        active: ActiveFollower,
        fix: LocationFix,
    ): FollowerSnapshot? {
        val handle = active.handle ?: return null
        val outcome =
            if (active.started) {
                handle.update(fix)
            } else {
                active.started = true
                handle.initialFix(fix)
            }
        return when (outcome) {
            is Outcome.Success -> {
                outcome.value
            }

            is Outcome.Failure -> {
                log("navigation-follower-failed error=${outcome.error}")
                active.close()
                null
            }
        }
    }

    private fun relationOf(offRouteStreak: Int): RouteRelation =
        when {
            offRouteStreak >= config.offRouteConfirmFixes -> RouteRelation.OffRoute
            offRouteStreak > 0 -> RouteRelation.OffStepOnRoute
            else -> RouteRelation.OnRoute
        }

    private fun isOffRoute(
        snapshot: FollowerSnapshot?,
        distanceMeters: Double?,
    ): Boolean {
        val distance = distanceMeters ?: snapshot?.deviationMeters ?: 0.0
        return when (snapshot?.deviation) {
            FollowerDeviation.OffRoute -> {
                true
            }

            FollowerDeviation.OffStepOnRoute -> {
                false
            }

            FollowerDeviation.None, FollowerDeviation.Unknown, null -> {
                distance > config.maxDeviationMeters
            }
        }
    }

    private fun arrivalOf(
        snapshot: FollowerSnapshot?,
        projection: PolylineLocator.Projection?,
    ): Boolean {
        if (snapshot?.arrived == true) {
            return true
        }
        val remaining = snapshot?.remainingDistanceMeters ?: projection?.remainingMeters ?: return false
        return remaining <= config.arrivalDistanceMeters
    }

    private fun stateOf(
        phase: NavigationPhase,
        fix: LocationFix?,
        snapshot: FollowerSnapshot?,
        projection: PolylineLocator.Projection?,
        relation: RouteRelation,
        route: Route,
        progress: RouteProgress,
        locator: PolylineLocator,
        revision: Long,
        rerouteCount: Int,
        lastRerouteMillis: Long?,
    ): NavigationState {
        val along = alongMeters(progress, locator, snapshot, projection)
        val remainingSeconds = progress.remainingSeconds(along)
        return NavigationState(
            phase = phase,
            fix = fix,
            snappedPosition = snapshot?.snappedPosition ?: projection?.position,
            relation = relation,
            distanceFromRouteMeters = projection?.distanceMeters ?: snapshot?.deviationMeters ?: 0.0,
            remainingDistanceMeters = progress.remainingMeters(along),
            remainingDurationSeconds = remainingSeconds,
            etaEpochMillis = fix?.let { it.timeMillis + (remainingSeconds * MILLIS_PER_SECOND).roundToLong() },
            nextInstruction = instructionAhead(route, along, snapshot),
            activeRoute = route,
            routeRevision = revision,
            rerouteCount = rerouteCount,
            lastRerouteMillis = lastRerouteMillis,
            profile = profile,
        )
    }

    /**
     * The distance along the route the fix has reached, in the route's own
     * distance space. The follower's remaining distance is preferred because
     * its numbers come from the same model the route was converted from; the
     * geometry projection is the fallback for an engine that reports none.
     */
    private fun alongMeters(
        progress: RouteProgress,
        locator: PolylineLocator,
        snapshot: FollowerSnapshot?,
        projection: PolylineLocator.Projection?,
    ): Double {
        val total = progress.totalDistanceMeters
        val fromFollower = snapshot?.remainingDistanceMeters
        if (fromFollower != null && fromFollower.isFinite()) {
            return total - fromFollower.coerceIn(0.0, total)
        }
        val lineMeters = locator.totalMeters()
        if (projection != null && lineMeters > 0.0) {
            return total * (projection.alongMeters / lineMeters).coerceIn(0.0, 1.0)
        }
        return 0.0
    }

    private fun instructionAhead(
        route: Route,
        alongMeters: Double,
        snapshot: FollowerSnapshot?,
    ): NavigationInstruction? {
        val upcoming =
            route.instructions
                .filter { it.distanceAlongRouteMeters > alongMeters + INSTRUCTION_LOOKAHEAD_METERS }
                .minByOrNull { it.distanceAlongRouteMeters }
        val text = snapshot?.nextInstruction?.takeIf { it.isNotBlank() } ?: upcoming?.text ?: return null
        return NavigationInstruction(
            text = text,
            streetName = upcoming?.streetName,
            distanceToInstructionMeters =
                snapshot?.distanceToNextInstructionMeters
                    ?: upcoming?.let { it.distanceAlongRouteMeters - alongMeters },
        )
    }

    /**
     * Publishes one state. A paused session keeps the paused phase for every
     * non-terminal update, so a reroute that was already in flight when pause
     * was pressed cannot publish a following or rerouting phase over it.
     */
    private fun publish(state: NavigationState) {
        val paused = control.paused.value
        val phase =
            if (paused && !isTerminal(state.phase)) {
                NavigationPhase.Paused
            } else {
                state.phase
            }
        mutableState.value = state.copy(phase = phase)
    }

    private fun isTerminal(phase: NavigationPhase): Boolean = phase == NavigationPhase.Arrived || phase == NavigationPhase.Stopped

    private fun format(value: Double?): String = if (value == null) "-" else String.format(Locale.ROOT, "%.1f", value)

    private class ActiveFollower(
        var handle: RouteFollowerHandle?,
        var started: Boolean = false,
    ) {
        fun close() {
            handle?.close()
            handle = null
            started = false
        }
    }

    private companion object {
        const val SESSION_ID = "navigation.session"
        const val MIN_ROUTE_POINTS = 2
        const val INSTRUCTION_LOOKAHEAD_METERS = 1.0
        const val MILLIS_PER_SECOND = 1_000.0

        /** A leg whose end is within this of the fix counts as not yet passed. */
        const val LEG_PASSED_EPSILON_METERS = 1.0
    }
}
