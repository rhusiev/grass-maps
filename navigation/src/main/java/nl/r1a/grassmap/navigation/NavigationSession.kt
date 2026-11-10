package nl.r1a.grassmap.navigation

import kotlinx.coroutines.CancellationException
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
 * verdict, including its own horizontal-accuracy gate. A fix must be off route
 * for [NavigationConfig.offRouteConfirmFixes] in a row before a reroute starts.
 * That is deliberately simpler than the 20 s profile-scaled, debounced policy
 * T-0312 owns.
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
        val destination = initialRoute.geometry.last()
        var activeRoute = initialRoute
        var locator = PolylineLocator(activeRoute.geometry)
        var progress = RouteProgress.of(activeRoute)
        log(
            "navigation-model source=${progress.source} spans=${progress.spanCount} " +
                "total_m=${format(progress.totalDistanceMeters)} total_s=${format(activeRoute.durationSeconds)}",
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
                            progress = progress,
                            locator = locator,
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
                                progress = RouteProgress.of(replacement)
                                revision += 1
                                rerouteCount += 1
                                fixesSinceReroute = 0
                                active = open(replacement)
                                lastRerouteMillis = clockMillis() - started
                                projection = locator.locate(fix.position)
                                snapshot = read(active, fix)
                                // The replacement line starts where Valhalla
                                // snapped the off-route fix, so the fix can
                                // still be off the new line. Do not report
                                // on-route until the geometry says so.
                                offRouteStreak =
                                    if (isOffRoute(snapshot, projection?.distanceMeters)) 1 else 0
                                log(
                                    "navigation-reroute fixes=$fixCount revision=$revision " +
                                        "duration_ms=$lastRerouteMillis " +
                                        "distance_m=${format(replacement.distanceMeters)} " +
                                        "model=${progress.source} spans=${progress.spanCount} " +
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
                offRouteFixCount = offRouteFixCount,
                rerouteCount = rerouteCount,
                arrived = arrived,
                detectionMillis = detectionMillis,
                lastRerouteMillis = lastRerouteMillis,
                finalRoute = activeRoute,
            ),
        )
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
        const val SESSION_ID = "navigation.session"
        const val MIN_ROUTE_POINTS = 2
        const val INSTRUCTION_LOOKAHEAD_METERS = 1.0
        const val MILLIS_PER_SECOND = 1_000.0
    }
}
