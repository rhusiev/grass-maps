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
import nl.r1a.grassmap.plugin.api.host.NoRouteHistoryAccess
import nl.r1a.grassmap.plugin.api.host.RouteHistoryAccess
import nl.r1a.grassmap.plugin.api.model.RouteHistoryEntry
import nl.r1a.grassmap.plugin.api.model.RoutePlanFailure
import nl.r1a.grassmap.plugin.api.model.RoutePlanOption
import nl.r1a.grassmap.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanRole
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteOptions
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.service.Clock
import nl.r1a.grassmap.plugin.api.service.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * The one planning state behind the start/end preview (T-0302), with
 * intermediate stops (T-0303) and an edit history (T-0304).
 *
 * Two ends and any number of vias go in, one route request goes out through
 * [requestRoute], and the engine's own answer list becomes the selectable
 * options. The planner owns no engine type, so the `planner` module supplies
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
 *
 * [undo] and [redo] step through whole published states, so the route a request
 * produced is part of its edit. An edit's snapshots are kept in memory only and
 * bounded; the planner asks no engine when it restores one, because the line the
 * user saw is what undo must return and a second request could return another
 * route.
 *
 * Routing preferences are per profile (T-0308) with three independent steps
 * (T-0421). [setPreferences] keeps an override for this plan only and
 * [resetPreferences] returns to the profile on the next request; neither writes
 * the settings model. [clear] drops the override with the plan, so the next
 * route starts from the profile again.
 */
class RoutePlanner(
    private val requestRoute: suspend (RouteRequest, CancellationHandle) -> Outcome<List<Route>>,
    private val clock: Clock = SystemClock,
    private val profile: RouteProfile = RouteProfile.Walk,
    private val optionsForProfile: (RouteProfile) -> RouteOptions = { RouteOptions() },
    private val alternatives: Int = 1,
    private val history: RouteHistoryAccess = NoRouteHistoryAccess,
) {
    init {
        require(alternatives >= 0) { "alternatives must not be negative" }
    }

    private val mutex = Mutex()
    private val ticket = AtomicLong(0)
    private val _state = MutableStateFlow(RoutePlanState(profile = profile))
    private val undoStack = ArrayDeque<RoutePlanState>()
    private val redoStack = ArrayDeque<RoutePlanState>()
    private val _recent = MutableStateFlow<List<RouteHistoryEntry>>(emptyList())
    private var generation = 0L

    val state: StateFlow<RoutePlanState> = _state.asStateFlow()

    /** The routes the host kept for reuse, newest first. */
    val recent: StateFlow<List<RouteHistoryEntry>> = _recent.asStateFlow()

    /** Reads the stored history once; the plugin calls this on activation. */
    suspend fun loadHistory() {
        val listed = history.recent()
        if (listed is Outcome.Success) {
            _recent.value = listed.value
        }
    }

    /** Opens the planner; a complete pair re-routes instead of asking again. */
    suspend fun begin(): Outcome<RoutePlanState> {
        val snapshot =
            mutex.withLock {
                ticket.incrementAndGet()
                val current = _state.value
                val complete = current.start != null && current.destination != null
                setState(
                    current.copy(
                        status = if (complete) RoutePlanStatus.Planning else nextPickingStatus(current),
                        options = emptyList(),
                        selectedIndex = 0,
                        failure = null,
                    ),
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
                rememberEdit(current)
                val complete = next.start != null && next.destination != null
                setState(
                    next.copy(
                        status = if (complete) RoutePlanStatus.Planning else nextPickingStatus(next),
                        options = emptyList(),
                        selectedIndex = 0,
                        failure = null,
                    ),
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
                setState(
                    current.copy(
                        status = RoutePlanStatus.Planning,
                        options = emptyList(),
                        selectedIndex = 0,
                        failure = null,
                    ),
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
                rememberEdit(current)
                val complete = next.start != null && next.destination != null
                setState(
                    next.copy(
                        status = if (complete) RoutePlanStatus.Planning else nextPickingStatus(next),
                        failure = null,
                    ),
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
                rememberEdit(current)
                val complete = updated.start != null && updated.destination != null
                setState(
                    updated.copy(
                        status = if (complete) RoutePlanStatus.Planning else nextPickingStatus(updated),
                        options = emptyList(),
                        selectedIndex = 0,
                        failure = null,
                    ),
                )
                _state.value
            } ?: return Outcome.Failure(AppError.InvalidInput)
        return if (snapshot.status == RoutePlanStatus.Planning) request(snapshot) else Outcome.Success(snapshot)
    }

    /**
     * Steps back one edit, restoring the whole state that edit produced
     * (including its route) without asking the engine. Five edits, five undos
     * and five redos end at the same states; the flags in the published state
     * say how many steps are left.
     */
    suspend fun undo(): Outcome<RoutePlanState> =
        mutex.withLock {
            val restored = undoStack.removeLastOrNull() ?: return@withLock Outcome.Failure(AppError.InvalidInput)
            ticket.incrementAndGet()
            redoStack.addLast(_state.value)
            setState(restored)
            Outcome.Success(_state.value)
        }

    /** Steps forward to the state an undo left, the same way [undo] steps back. */
    suspend fun redo(): Outcome<RoutePlanState> =
        mutex.withLock {
            val restored = redoStack.removeLastOrNull() ?: return@withLock Outcome.Failure(AppError.InvalidInput)
            ticket.incrementAndGet()
            undoStack.addLast(_state.value)
            setState(restored)
            Outcome.Success(_state.value)
        }

    /**
     * Replaces the chain with a stored entry and routes it, as one edit so one
     * undo returns to the plan the user had. The stored totals are not reused:
     * the engine resolves the chain again against the current map data. The
     * entry's preferences travel with the chain, so a reuse restores the route
     * the user chose rather than today's profile defaults (T-0421).
     */
    suspend fun reuse(entry: RouteHistoryEntry): Outcome<RoutePlanState> {
        val snapshot =
            mutex.withLock {
                val current = _state.value
                rememberEdit(current)
                ticket.incrementAndGet()
                setState(
                    current.copy(
                        start = entry.start,
                        destination = entry.destination,
                        vias = entry.vias,
                        status = RoutePlanStatus.Planning,
                        options = emptyList(),
                        selectedIndex = 0,
                        failure = null,
                        overridePreferences = entry.preferences,
                    ),
                )
                _state.value
            }
        return request(snapshot)
    }

    /**
     * Applies [options] to this plan only and routes it again. The settings
     * model is never written, so the profile keeps its defaults. A value equal
     * to the profile defaults is stored as no override, which is what the
     * preview's Reset action produces after the user picks every Normal.
     */
    suspend fun setPreferences(options: RouteOptions): Outcome<RoutePlanState> = revisePreferences(options)

    /** Drops the one-route override and routes with the profile defaults again. */
    suspend fun resetPreferences(): Outcome<RoutePlanState> = revisePreferences(null)

    private suspend fun revisePreferences(override: RouteOptions?): Outcome<RoutePlanState> {
        val current = _state.value
        if (current.overridePreferences == override) {
            return Outcome.Success(current)
        }
        val snapshot =
            mutex.withLock {
                ticket.incrementAndGet()
                val currentState = _state.value
                val profileDefaults = optionsForProfile(profile)
                val nextOverride = override?.takeUnless { value -> value == profileDefaults }
                rememberEdit(currentState)
                val complete = currentState.start != null && currentState.destination != null
                setState(
                    currentState.copy(
                        overridePreferences = nextOverride,
                        status = if (complete) RoutePlanStatus.Planning else nextPickingStatus(currentState),
                        options = emptyList(),
                        selectedIndex = 0,
                        failure = null,
                    ),
                )
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
            setState(current.copy(selectedIndex = index))
            Outcome.Success(_state.value)
        }

    /** Closes the plan, cancels any request still in flight and drops the edit history. */
    suspend fun clear(): Outcome<RoutePlanState> {
        ticket.incrementAndGet()
        return mutex.withLock {
            undoStack.clear()
            redoStack.clear()
            setState(RoutePlanState(profile = profile))
            Outcome.Success(_state.value)
        }
    }

    private suspend fun request(snapshot: RoutePlanState): Outcome<RoutePlanState> {
        if (snapshot.start == null || snapshot.destination == null) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val mine = ticket.incrementAndGet()
        val cancellation = CancellationHandle { ticket.get() != mine }
        val profilePreferences = optionsForProfile(profile)
        val effective = snapshot.overridePreferences ?: profilePreferences
        val outcome =
            try {
                requestRoute(
                    RouteRequest(
                        waypoints = snapshot.stops.map { stop -> stop.position },
                        profile = profile,
                        alternatives = alternatives,
                        options = effective,
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
                    setState(
                        _state.value.copy(
                            status = RoutePlanStatus.Failed,
                            options = emptyList(),
                            failure = failureOf(outcome.error),
                            profilePreferences = profilePreferences,
                            overridePreferences = snapshot.overridePreferences,
                        ),
                    )
                    outcome
                }

                is Outcome.Success -> {
                    publishRoutes(outcome.value, profilePreferences, snapshot.overridePreferences)
                }
            }
        }
    }

    private suspend fun publishRoutes(
        routes: List<Route>,
        profilePreferences: RouteOptions,
        overridePreferences: RouteOptions?,
    ): Outcome<RoutePlanState> {
        val usable = routes.filter { route -> route.geometry.size >= MIN_ROUTE_POINTS }
        if (usable.isEmpty()) {
            setState(
                _state.value.copy(
                    status = RoutePlanStatus.Failed,
                    options = emptyList(),
                    failure = RoutePlanFailure.NoRoute,
                    profilePreferences = profilePreferences,
                    overridePreferences = overridePreferences,
                ),
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
        setState(
            _state.value.copy(
                status = RoutePlanStatus.Ready,
                options = options,
                selectedIndex = 0,
                generation = generation,
                failure = null,
                profile = profile,
                profilePreferences = profilePreferences,
                overridePreferences = overridePreferences,
            ),
        )
        rememberRoute(_state.value)
        return Outcome.Success(_state.value)
    }

    /**
     * Publishes [value] with the edit history's current reach. The flags are
     * derived, never stored in a snapshot, so a restore cannot carry a stale
     * count.
     */
    private fun setState(value: RoutePlanState) {
        _state.value =
            value.copy(
                undoAvailable = undoStack.isNotEmpty(),
                redoAvailable = redoStack.isNotEmpty(),
            )
    }

    /** Keeps the state an edit replaces; a new edit drops anything redoable. */
    private fun rememberEdit(before: RoutePlanState) {
        undoStack.addLast(before)
        if (undoStack.size > MAX_UNDO_STEPS) {
            undoStack.removeFirst()
        }
        redoStack.clear()
    }

    /**
     * Records a produced route for reuse. History is auxiliary: a store that is
     * missing or refuses must not fail the plan the user asked for.
     */
    private suspend fun rememberRoute(state: RoutePlanState) {
        val start = state.start ?: return
        val destination = state.destination ?: return
        val selected = state.selected ?: return
        val entry =
            RouteHistoryEntry(
                start = start,
                destination = destination,
                vias = state.vias,
                distanceMeters = selected.distanceMeters,
                durationSeconds = selected.durationSeconds,
                preferences = state.preferences,
            )
        val stored = history.remember(entry)
        if (stored is Outcome.Success) {
            _recent.value = stored.value
        }
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

        /**
         * How many edits can be undone. A snapshot carries a route geometry, so
         * the bound keeps the worst case at a few megabytes; five is what the
         * acceptance exercises and ten leaves room for ordinary editing.
         */
        const val MAX_UNDO_STEPS = 10
    }
}
