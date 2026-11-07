package nl.r1a.grassmap.navigation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine
import java.util.Locale

/**
 * The navigation seam: one active route, a stream of fixes, an engine-neutral
 * state, and a reroute through [RoutingEngine] when the route is left.
 *
 * The off-route decision uses Ferrostar's deviation verdict when the follower
 * is available and a [PolylineLocator] distance as the floor and the fallback.
 * A fix must be off route for [NavigationConfig.offRouteConfirmFixes] in a row
 * before a reroute starts. That is deliberately simpler than the 20 s
 * profile-scaled, debounced policy T-0312 owns.
 */
class NavigationSession(
    private val follower: RouteFollowerEngine,
    private val routing: RoutingEngine,
    private val profile: RouteProfile,
    private val config: NavigationConfig = NavigationConfig(),
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val log: (String) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(NavigationState())

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
        val destination = initialRoute.geometry.last()
        var activeRoute = initialRoute
        var locator = PolylineLocator(activeRoute.geometry)
        var active = open(activeRoute)
        var revision = 0L
        var rerouteCount = 0
        var fixCount = 0
        var offRouteFixCount = 0
        var offRouteStreak = 0
        var fixesSinceReroute = config.rerouteCooldownFixes
        var detectionMillis: Long? = null
        var lastRerouteMillis: Long? = null
        var arrived = false
        var previousRelation = RouteRelation.Unknown
        val sessionStartedMillis = clockMillis()

        try {
            fixes.takeWhile { !arrived }.collect { fix ->
                fixCount += 1
                fixesSinceReroute += 1
                var projection = locator.locate(fix.position)
                var snapshot = read(active, fix)

                val candidateOffRoute = isOffRoute(snapshot, projection?.distanceMeters)
                offRouteStreak = if (candidateOffRoute) offRouteStreak + 1 else 0
                if (candidateOffRoute) {
                    offRouteFixCount += 1
                }

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
                if (confirmed && config.reroute && fixesSinceReroute >= config.rerouteCooldownFixes) {
                    publish(
                        stateOf(
                            phase = NavigationPhase.Rerouting,
                            fix = fix,
                            snapshot = snapshot,
                            projection = projection,
                            relation = RouteRelation.OffRoute,
                            route = activeRoute,
                            revision = revision,
                            rerouteCount = rerouteCount,
                            lastRerouteMillis = lastRerouteMillis,
                        ),
                    )
                    val started = clockMillis()
                    val request = RouteRequest(waypoints = listOf(fix.position, destination), profile = profile)
                    when (val outcome = routing.route(request, CancellationHandle.None)) {
                        is Outcome.Failure -> {
                            log("navigation-reroute-failed fixes=$fixCount error=${outcome.error}")
                            fixesSinceReroute = 0
                        }

                        is Outcome.Success -> {
                            val replacement = outcome.value.firstOrNull { it.geometry.size >= MIN_ROUTE_POINTS }
                            if (replacement == null) {
                                log("navigation-reroute-failed fixes=$fixCount error=${AppError.NotFound}")
                                fixesSinceReroute = 0
                            } else {
                                active.close()
                                activeRoute = replacement
                                locator = PolylineLocator(replacement.geometry)
                                revision += 1
                                rerouteCount += 1
                                fixesSinceReroute = 0
                                offRouteStreak = 0
                                active = open(replacement)
                                lastRerouteMillis = clockMillis() - started
                                projection = locator.locate(fix.position)
                                snapshot = read(active, fix)
                                log(
                                    "navigation-reroute fixes=$fixCount revision=$revision " +
                                        "duration_ms=$lastRerouteMillis " +
                                        "distance_m=${format(replacement.distanceMeters)} " +
                                        "destination=${format(destination.latitude)},${format(destination.longitude)}",
                                )
                            }
                        }
                    }
                }

                val relation =
                    when {
                        offRouteStreak >= config.offRouteConfirmFixes -> RouteRelation.OffRoute
                        offRouteStreak > 0 -> RouteRelation.OffStepOnRoute
                        else -> RouteRelation.OnRoute
                    }
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
                        revision = revision,
                        rerouteCount = rerouteCount,
                        lastRerouteMillis = lastRerouteMillis,
                    ),
                )
                if (arrived) {
                    log(
                        "navigation-arrived fixes=$fixCount " +
                            "remaining_m=${format(snapshot?.remainingDistanceMeters ?: projection?.remainingMeters)}",
                    )
                }
            }
        } finally {
            active.close()
        }

        publish(
            stateOf(
                phase = if (arrived) NavigationPhase.Arrived else NavigationPhase.Stopped,
                fix = mutableState.value.fix,
                snapshot = null,
                projection = null,
                relation = mutableState.value.relation,
                route = activeRoute,
                revision = revision,
                rerouteCount = rerouteCount,
                lastRerouteMillis = lastRerouteMillis,
            ),
        )
        return Outcome.Success(
            NavigationRun(
                fixCount = fixCount,
                offRouteFixCount = offRouteFixCount,
                rerouteCount = rerouteCount,
                arrived = arrived,
                detectionMillis = detectionMillis,
                lastRerouteMillis = lastRerouteMillis,
                finalRoute = activeRoute,
            ),
        )
    }

    private fun open(route: Route): ActiveFollower =
        when (val outcome = follower.begin(route)) {
            is Outcome.Success -> {
                ActiveFollower(outcome.value)
            }

            is Outcome.Failure -> {
                log("navigation-follower-unavailable error=${outcome.error}")
                ActiveFollower(handle = null)
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
        revision: Long,
        rerouteCount: Int,
        lastRerouteMillis: Long?,
    ): NavigationState {
        val remaining = snapshot?.remainingDistanceMeters ?: projection?.remainingMeters ?: 0.0
        return NavigationState(
            phase = phase,
            fix = fix,
            snappedPosition = snapshot?.snappedPosition ?: projection?.position,
            relation = relation,
            distanceFromRouteMeters = projection?.distanceMeters ?: snapshot?.deviationMeters ?: 0.0,
            remainingDistanceMeters = remaining,
            remainingDurationSeconds =
                snapshot?.remainingDurationSeconds
                    ?: remainingDuration(route, remaining),
            nextInstruction = instructionAhead(route, projection?.alongMeters, snapshot),
            activeRoute = route,
            routeRevision = revision,
            rerouteCount = rerouteCount,
            lastRerouteMillis = lastRerouteMillis,
        )
    }

    private fun remainingDuration(
        route: Route,
        remainingMeters: Double,
    ): Double {
        if (route.distanceMeters <= 0.0) {
            return route.durationSeconds
        }
        return route.durationSeconds * (remainingMeters / route.distanceMeters).coerceIn(0.0, 1.0)
    }

    private fun instructionAhead(
        route: Route,
        alongMeters: Double?,
        snapshot: FollowerSnapshot?,
    ): NavigationInstruction? {
        val along = alongMeters ?: 0.0
        val upcoming =
            route.instructions
                .filter { it.distanceAlongRouteMeters > along + INSTRUCTION_LOOKAHEAD_METERS }
                .minByOrNull { it.distanceAlongRouteMeters }
        val text = snapshot?.nextInstruction?.takeIf { it.isNotBlank() } ?: upcoming?.text ?: return null
        return NavigationInstruction(
            text = text,
            streetName = upcoming?.streetName,
            distanceToInstructionMeters =
                snapshot?.distanceToNextInstructionMeters
                    ?: upcoming?.let { it.distanceAlongRouteMeters - along },
        )
    }

    private fun publish(state: NavigationState) {
        mutableState.value = state
    }

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
        const val MIN_ROUTE_POINTS = 2
        const val INSTRUCTION_LOOKAHEAD_METERS = 1.0
    }
}
