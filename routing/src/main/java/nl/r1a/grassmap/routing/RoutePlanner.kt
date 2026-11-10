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
 * The one planning state behind the start/end preview (T-0302).
 *
 * Two points go in, one route request goes out through [requestRoute], and the
 * engine's own answer list becomes the selectable options. The planner owns no
 * engine type, so `plugins/route-planner` supplies the resolved engine and a
 * test supplies a fixed one.
 *
 * A start and a destination are the whole request. [swap] swaps the points and
 * routes again; it never reverses a geometry, because a reversed line can
 * break one-way rules the engine knows about. A point change or a newer
 * request invalidates an older in-flight request, so a slow answer cannot
 * overwrite the state a later edit produced.
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
                    )
                _state.value
            }
        return if (snapshot.status == RoutePlanStatus.Planning) request(snapshot) else Outcome.Success(snapshot)
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
                    )
                _state.value
            }
        return request(snapshot)
    }

    /** Swaps the two ends and routes the reversed pair through the engine. */
    suspend fun swap(): Outcome<RoutePlanState> {
        val snapshot =
            mutex.withLock {
                ticket.incrementAndGet()
                val current = _state.value
                val next =
                    current.copy(
                        start = current.destination,
                        destination = current.start,
                        options = emptyList(),
                        selectedIndex = 0,
                    )
                val complete = next.start != null && next.destination != null
                _state.value = next.copy(status = if (complete) RoutePlanStatus.Planning else nextPickingStatus(next))
                _state.value
            }
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
        val start = snapshot.start ?: return Outcome.Failure(AppError.InvalidInput)
        val destination = snapshot.destination ?: return Outcome.Failure(AppError.InvalidInput)
        val mine = ticket.incrementAndGet()
        val cancellation = CancellationHandle { ticket.get() != mine }
        val outcome =
            try {
                requestRoute(
                    RouteRequest(
                        waypoints = listOf(start.position, destination.position),
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
                    _state.value = _state.value.copy(status = RoutePlanStatus.Failed, options = emptyList())
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
            _state.value = _state.value.copy(status = RoutePlanStatus.Failed, options = emptyList())
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
                )
            }
        generation += 1
        _state.value =
            _state.value.copy(
                status = RoutePlanStatus.Ready,
                options = options,
                selectedIndex = 0,
                generation = generation,
            )
        return Outcome.Success(_state.value)
    }

    private fun nextPickingStatus(state: RoutePlanState): RoutePlanStatus =
        if (state.start == null) RoutePlanStatus.PickingStart else RoutePlanStatus.PickingDestination

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0
        const val MIN_ROUTE_POINTS = 2
    }
}
