package nl.r1a.grassmap.routing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.RoutePlanFailure
import nl.r1a.grassmap.plugin.api.model.RoutePlanOption
import nl.r1a.grassmap.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanRole
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.service.Clock
import nl.r1a.grassmap.plugin.api.service.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * The one planning state behind the start/end preview (T-0302), with
 * intermediate stops (T-0303).
 *
 * Two ends and any number of vias go in, one route request goes out through
 * [requestRoute], and the engine's own answer list becomes the selectable
 * options. The planner owns no engine type, so `plugins/route-planner` supplies
 * the resolved engine and a test supplies a fixed one.
 *
 * A start, the vias in visit order and a destination are one ordered waypoint
 * chain, never a sequence of separately routed hops: the engine returns one
 * route whose segments are its legs, so distance and duration are totals for the
 * whole journey. [swap] swaps the points and reverses the via order, then routes
 * again; it never reverses a geometry, because a reversed line can break one-way
 * rules the engine knows about. A point change, a via edit or a newer request
 * invalidates an older in-flight request, so a slow answer cannot overwrite the
 * state a later edit produced.
 */
class RoutePlanner(
    private val requestRoute: suspend (RouteRequest, CancellationHandle) -> Outcome<List<Route>>,
    private val clock: Clock = SystemClock,
    private val profile: RouteProfile = RouteProfile.Walk,
    private val alternatives: Int = 1,
) {
    init {
        require(alternatives >= 0) { "alternatives must not be negative" }
    }

    private val mutex = Mutex()
    private val ticket = AtomicLong(0)
    private val _state = MutableStateFlow(RoutePlanState())
    private var generation = 0L

    val state: StateFlow<RoutePlanState> = _state.asStateFlow()

    /** Opens the planner; a complete pair re-routes instead of asking again. */
    suspend fun begin(): Outcome<RoutePlanState> {
        val snapshot =
            mutex.withLock {
                ticket.incrementAndGet()
                val current = _state.value
                val complete = current.start != null && current.destination != null
                _state.value =
                    current.copy(
                        status = if (complete) RoutePlanStatus.Planning else nextPickingStatus(current),
                        options = emptyList(),
                        selectedIndex = 0,
                        failure = null,
                    )
                _state.value
            }
        return if (snapshot.status == RoutePlanStatus.Planning) request(snapshot) else Outcome.Success(snapshot)
    }

    /** Fills one end; when both are known the route is requested at once. */
    suspend fun setPoint(
        role: RoutePlanRole,
        point: RoutePlanPoint,
    ): Outcome<RoutePlanState> {
        val snapshot =
            mutex.withLock {
                ticket.incrementAndGet()
                val current = _state.value
                val next =
                    when (role) {
                        RoutePlanRole.Start -> current.copy(start = point)
                        RoutePlanRole.Destination -> current.copy(destination = point)
                    }
                val complete = next.start != null && next.destination != null
                _state.value =
                    next.copy(
                        status = if (complete) RoutePlanStatus.Planning else nextPickingStatus(next),
                        options = emptyList(),
                        selectedIndex = 0,
                        failure = null,
                    )
                _state.value
            }
        return if (snapshot.status == RoutePlanStatus.Planning) request(snapshot) else Outcome.Success(snapshot)
    }

    /** Adds one intermediate stop at the end of the visit order, then routes. */
    suspend fun addVia(point: RoutePlanPoint): Outcome<RoutePlanState> = revise { current -> current.copy(vias = current.vias + point) }

    /** Removes the via at [index]; the remaining stops keep their order. */
    suspend fun removeVia(index: Int): Outcome<RoutePlanState> =
        revise { current ->
            if (index !in current.vias.indices) {
                null
            } else {
                current.copy(vias = current.vias.filterIndexed { at, _ -> at != index })
            }
        }

    /**
     * Moves the via at [from] to position [to], which is what a manual reorder
     * does. The route is requested again, so the line follows the new order.
     */
    suspend fun moveVia(
        from: Int,
        to: Int,
    ): Outcome<RoutePlanState> {
        val current = _state.value
        if (from == to && from in current.vias.indices) {
            return Outcome.Success(current)
        }
        return revise { state ->
            if (from !in state.vias.indices || to !in state.vias.indices) {
                null
            } else {
                state.copy(
                    vias =
                        state.vias.toMutableList().apply {
                            add(to, removeAt(from))
                        },
                )
            }
        }
    }

    /** Requests the current pair again, for a retry after a failure. */
    suspend fun plan(): Outcome<RoutePlanState> {
        val snapshot =
            mutex.withLock {
                ticket.incrementAndGet()
                val current = _state.value
                _state.value =
                    current.copy(
                        status = RoutePlanStatus.Planning,
                        options = emptyList(),
                        selectedIndex = 0,
                        failure = null,
                    )
                _state.value
            }
        return request(snapshot)
    }

    /**
     * Swaps the two ends and routes the reversed journey, so the vias keep their
     * relative order: the last stop visited becomes the first.
     */
    suspend fun swap(): Outcome<RoutePlanState> {
        val snapshot =
            mutex.withLock {
                ticket.incrementAndGet()
                val current = _state.value
                val next =
                    current.copy(
                        start = current.destination,
                        destination = current.start,
                        vias = current.vias.reversed(),
                        options = emptyList(),
                        selectedIndex = 0,
                    )
                val complete = next.start != null && next.destination != null
                _state.value =
                    next.copy(
                        status = if (complete) RoutePlanStatus.Planning else nextPickingStatus(next),
                        failure = null,
                    )
                _state.value
            }
        return if (snapshot.status == RoutePlanStatus.Planning) request(snapshot) else Outcome.Success(snapshot)
    }

    /**
     * Applies one via edit and routes the resulting chain. An edit that does not
     * apply is [AppError.InvalidInput]; one that leaves an end unset keeps the
     * plan picking instead of requesting a partial route.
     */
    private suspend fun revise(update: (RoutePlanState) -> RoutePlanState?): Outcome<RoutePlanState> {
        val snapshot =
            mutex.withLock {
                ticket.incrementAndGet()
                val current = _state.value
                val updated = update(current) ?: return@withLock null
                val complete = updated.start != null && updated.destination != null
                _state.value =
                    updated.copy(
                        status = if (complete) RoutePlanStatus.Planning else nextPickingStatus(updated),
                        options = emptyList(),
                        selectedIndex = 0,
                        failure = null,
                    )
                _state.value
            } ?: return Outcome.Failure(AppError.InvalidInput)
        return if (snapshot.status == RoutePlanStatus.Planning) request(snapshot) else Outcome.Success(snapshot)
    }

    /** Selects one returned alternative; the drawn line follows [state]. */
    suspend fun select(index: Int): Outcome<RoutePlanState> =
        mutex.withLock {
            val current = _state.value
            if (current.status != RoutePlanStatus.Ready || index !in current.options.indices) {
                return@withLock Outcome.Failure(AppError.InvalidInput)
            }
            _state.value = current.copy(selectedIndex = index)
            Outcome.Success(_state.value)
        }

    /** Closes the plan and cancels any request still in flight. */
    suspend fun clear(): Outcome<RoutePlanState> {
        ticket.incrementAndGet()
        return mutex.withLock {
            _state.value = RoutePlanState()
            Outcome.Success(_state.value)
        }
    }

    private suspend fun request(snapshot: RoutePlanState): Outcome<RoutePlanState> {
        if (snapshot.start == null || snapshot.destination == null) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val mine = ticket.incrementAndGet()
        val cancellation = CancellationHandle { ticket.get() != mine }
        val outcome =
            try {
                requestRoute(
                    RouteRequest(
                        waypoints = snapshot.stops.map { stop -> stop.position },
                        profile = profile,
                        alternatives = alternatives,
                    ),
                    cancellation,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Outcome.Failure(AppError.Internal("route.planner", exception.message))
            }
        return mutex.withLock {
            if (ticket.get() != mine) {
                return@withLock Outcome.Failure(AppError.Cancelled)
            }
            when (outcome) {
                is Outcome.Failure -> {
                    _state.value =
                        _state.value.copy(
                            status = RoutePlanStatus.Failed,
                            options = emptyList(),
                            failure = failureOf(outcome.error),
                        )
                    outcome
                }

                is Outcome.Success -> {
                    publish(outcome.value)
                }
            }
        }
    }

    private fun publish(routes: List<Route>): Outcome<RoutePlanState> {
        val usable = routes.filter { route -> route.geometry.size >= MIN_ROUTE_POINTS }
        if (usable.isEmpty()) {
            _state.value =
                _state.value.copy(
                    status = RoutePlanStatus.Failed,
                    options = emptyList(),
                    failure = RoutePlanFailure.NoRoute,
                )
            return Outcome.Failure(AppError.NotFound)
        }
        val now = clock.nowMillis()
        val options =
            usable.map { route ->
                RoutePlanOption(
                    geometry = route.geometry,
                    distanceMeters = route.distanceMeters,
                    durationSeconds = route.durationSeconds,
                    arrivalEpochMillis = now + (route.durationSeconds * MILLIS_PER_SECOND).toLong(),
                    segments = route.segments,
                    instructions = route.instructions,
                )
            }
        generation += 1
        _state.value =
            _state.value.copy(
                status = RoutePlanStatus.Ready,
                options = options,
                selectedIndex = 0,
                generation = generation,
                failure = null,
            )
        return Outcome.Success(_state.value)
    }

    private fun failureOf(error: AppError): RoutePlanFailure =
        when (error) {
            is AppError.RoutingPackUnavailable -> {
                when (error.reason) {
                    AppError.RoutingPackUnavailable.Reason.Absent -> RoutePlanFailure.PackMissing
                    AppError.RoutingPackUnavailable.Reason.Incomplete -> RoutePlanFailure.PackMissing
                    AppError.RoutingPackUnavailable.Reason.Unusable -> RoutePlanFailure.PackUnusable
                }
            }

            AppError.NotFound,
            AppError.NotReady,
            is AppError.PluginFailed,
            -> {
                RoutePlanFailure.EngineUnavailable
            }

            else -> {
                RoutePlanFailure.NoRoute
            }
        }

    private fun nextPickingStatus(state: RoutePlanState): RoutePlanStatus =
        if (state.start == null) RoutePlanStatus.PickingStart else RoutePlanStatus.PickingDestination

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0
        const val MIN_ROUTE_POINTS = 2
    }
}
