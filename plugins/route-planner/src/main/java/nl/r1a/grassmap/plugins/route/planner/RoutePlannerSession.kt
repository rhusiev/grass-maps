package nl.r1a.grassmap.plugins.route.planner

import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.RoutingEngineAccess
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanRole
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.service.Clock
import nl.r1a.grassmap.routing.RoutePlanner

/**
 * The plugin's handle on the planner (T-0302). The state machine lives in
 * `routing`; this session resolves the engine through the host seam, clamps
 * the alternatives request to what the engine declares, and republishes every
 * answer as one stream.
 */
class RoutePlannerSession(
    private val engines: RoutingEngineAccess,
    clock: Clock,
) {
    private val planner =
        RoutePlanner(
            requestRoute = ::route,
            clock = clock,
            profile = RouteProfile.Walk,
            alternatives = DEFAULT_ALTERNATIVES,
        )

    val state = planner.state

    suspend fun begin(): Outcome<RoutePlanState> = planner.begin()

    suspend fun setPoint(
        role: RoutePlanRole,
        position: GeoPoint,
        label: String?,
    ): Outcome<RoutePlanState> = planner.setPoint(role, RoutePlanPoint(position, label.orEmpty()))

    suspend fun plan(): Outcome<RoutePlanState> = planner.plan()

    suspend fun swap(): Outcome<RoutePlanState> = planner.swap()

    suspend fun select(index: Int): Outcome<RoutePlanState> = planner.select(index)

    suspend fun clear(): Outcome<RoutePlanState> = planner.clear()

    private suspend fun route(
        request: RouteRequest,
        cancellation: CancellationHandle,
    ): Outcome<List<Route>> =
        when (val resolved = engines.engine()) {
            is Outcome.Failure -> {
                resolved
            }

            is Outcome.Success -> {
                val engine = resolved.value
                val alternatives = request.alternatives.coerceIn(0, engine.capabilities().maxAlternatives)
                engine.route(request.copy(alternatives = alternatives), cancellation)
            }
        }

    private companion object {
        const val DEFAULT_ALTERNATIVES = 1
    }
}
