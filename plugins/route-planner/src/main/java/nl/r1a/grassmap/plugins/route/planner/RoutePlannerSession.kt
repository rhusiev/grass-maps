package nl.r1a.grassmap.plugins.route.planner

import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.RouteHistoryAccess
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
 * answer as one stream. The reuse history arrives through the host seam too
 * (T-0304), so the plugin names no path and no clock.
 */
class RoutePlannerSession(
    private val engines: RoutingEngineAccess,
    clock: Clock,
    history: RouteHistoryAccess,
) {
    private val planner =
        RoutePlanner(
            requestRoute = ::route,
            clock = clock,
            profile = RouteProfile.Walk,
            alternatives = DEFAULT_ALTERNATIVES,
            history = history,
        )

    val state = planner.state

    /** The host's stored routes, newest first. */
    val recent = planner.recent

    suspend fun loadHistory() {
        planner.loadHistory()
    }

    suspend fun begin(): Outcome<RoutePlanState> = planner.begin()

    suspend fun setPoint(
        role: RoutePlanRole,
        position: GeoPoint,
        label: String?,
    ): Outcome<RoutePlanState> = planner.setPoint(role, RoutePlanPoint(position, label.orEmpty()))

    suspend fun addVia(
        position: GeoPoint,
        label: String?,
    ): Outcome<RoutePlanState> = planner.addVia(RoutePlanPoint(position, label.orEmpty()))

    suspend fun removeVia(index: Int): Outcome<RoutePlanState> = planner.removeVia(index)

    suspend fun moveVia(
        from: Int,
        to: Int,
    ): Outcome<RoutePlanState> = planner.moveVia(from, to)

    suspend fun plan(): Outcome<RoutePlanState> = planner.plan()

    suspend fun swap(): Outcome<RoutePlanState> = planner.swap()

    suspend fun select(index: Int): Outcome<RoutePlanState> = planner.select(index)

    suspend fun undo(): Outcome<RoutePlanState> = planner.undo()

    suspend fun redo(): Outcome<RoutePlanState> = planner.redo()

    /** Reopens the stored route at [index] as one edit; a bad index is invalid input. */
    suspend fun reuse(index: Int): Outcome<RoutePlanState> {
        val entry = planner.recent.value.getOrNull(index) ?: return Outcome.Failure(AppError.InvalidInput)
        return planner.reuse(entry)
    }

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
