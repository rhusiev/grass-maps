package nl.r1a.grassmaps.routing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.r1a.grassmaps.plugin.api.contract.CancellationHandle
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.host.NoRegionDownloadsAccess
import nl.r1a.grassmaps.plugin.api.host.NoRouteHistoryAccess
import nl.r1a.grassmaps.plugin.api.host.NoRoutingPacks
import nl.r1a.grassmaps.plugin.api.host.RegionDownloadsAccess
import nl.r1a.grassmaps.plugin.api.host.RouteHistoryAccess
import nl.r1a.grassmaps.plugin.api.host.RoutingPackAccess
import nl.r1a.grassmaps.plugin.api.model.DownloadProblem
import nl.r1a.grassmaps.plugin.api.model.DownloadProblemKind
import nl.r1a.grassmaps.plugin.api.model.MissingRegions
import nl.r1a.grassmaps.plugin.api.model.MissingRegionsDownload
import nl.r1a.grassmaps.plugin.api.model.RouteHistoryEntry
import nl.r1a.grassmaps.plugin.api.model.RoutePlanFailure
import nl.r1a.grassmaps.plugin.api.model.RoutePlanOption
import nl.r1a.grassmaps.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmaps.plugin.api.model.RoutePlanRole
import nl.r1a.grassmaps.plugin.api.model.RoutePlanState
import nl.r1a.grassmaps.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmaps.plugin.api.provider.Route
import nl.r1a.grassmaps.plugin.api.provider.RouteOptions
import nl.r1a.grassmaps.plugin.api.provider.RouteProfile
import nl.r1a.grassmaps.plugin.api.provider.RouteRequest
import nl.r1a.grassmaps.plugin.api.service.Clock
import nl.r1a.grassmaps.plugin.api.service.SystemClock
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
 * bounded. A restored snapshot that carries a route is published as it was,
 * because the line the user saw is what undo must return and a second request
 * could return another route. A snapshot that was still Planning never received
 * an answer, so the request it was waiting for starts again on the restored
 * chain.
 *
 * Routing preferences are per profile (T-0308) with three independent steps
 * (T-0421). [setPreferences] keeps an override for this plan only and
 * [resetPreferences] returns to the profile on the next request; neither writes
 * the settings model. [clear] drops the override with the plan, so the next
 * route starts from the profile again.
 *
 * A produced route is published before its runs are annotated (T-0409): the
 * engine's route answer carries no per-edge attributes, so the planner asks
 * [annotateRoute] for the selected route on [annotationScope] and replaces that
 * option's runs when the answer arrives. The legs stay as produced. The preview,
 * the map and the warnings are already usable in the meantime, and every other
 * part of the option - geometry, totals, legs, arrival, selection, the
 * alternatives and the history - is left as published. An annotate failure
 * keeps the unannotated route and reports it through [log]. Each produced option
 * is asked for once, so re-selecting it does not ask the engine again.
 */
class RoutePlanner(
    private val requestRoute: suspend (RouteRequest, CancellationHandle) -> Outcome<List<Route>>,
    private val clock: Clock = SystemClock,
    private val profile: RouteProfile = RouteProfile.Walk,
    private val optionsForProfile: (RouteProfile) -> RouteOptions = { RouteOptions() },
    private val alternatives: Int = 1,
    private val history: RouteHistoryAccess = NoRouteHistoryAccess,
    private val annotateRoute: suspend (Route, RouteProfile, CancellationHandle) -> Outcome<Route> =
        { route, _, _ -> Outcome.Success(route) },
    private val annotationScope: CoroutineScope? = null,
    private val log: (String) -> Unit = {},
    private val downloads: RegionDownloadsAccess = NoRegionDownloadsAccess,
    private val packs: RoutingPackAccess = NoRoutingPacks,
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

    /**
     * The option indexes whose annotate pass was requested, per produced
     * generation. Undo can restore an older generation, so the map keeps a few
     * of them; the bound is the only memory this holds.
     */
    private val annotatedIndexes = LinkedHashMap<Long, MutableSet<Int>>()

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
     * (including its route). A restored route is not asked for again; a restored
     * Planning state starts its request again. Five edits, five undos and five
     * redos end at the same states; the flags in the published state say how many
     * steps are left.
     */
    suspend fun undo(): Outcome<RoutePlanState> {
        val restored =
            mutex.withLock {
                val snapshot = undoStack.removeLastOrNull() ?: return@withLock null
                ticket.incrementAndGet()
                redoStack.addLast(_state.value)
                setState(snapshot)
                _state.value
            } ?: return Outcome.Failure(AppError.InvalidInput)
        return resumeRestore(restored)
    }

    /** Steps forward to the state an undo left, the same way [undo] steps back. */
    suspend fun redo(): Outcome<RoutePlanState> {
        val restored =
            mutex.withLock {
                val snapshot = redoStack.removeLastOrNull() ?: return@withLock null
                ticket.incrementAndGet()
                undoStack.addLast(_state.value)
                setState(snapshot)
                _state.value
            } ?: return Outcome.Failure(AppError.InvalidInput)
        return resumeRestore(restored)
    }

    /**
     * Finishes a restore. A snapshot with a route is annotated as published; a
     * snapshot that was Planning starts the request it was waiting for, and only
     * that request's ticket can publish, so the answer the restore superseded is
     * dropped.
     */
    private suspend fun resumeRestore(restored: RoutePlanState): Outcome<RoutePlanState> {
        if (restored.status == RoutePlanStatus.Planning) {
            return request(restored)
        }
        annotateSelected()
        return Outcome.Success(restored)
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
     * Setting what the plan already uses is not an edit: it adds no undo step
     * and asks the engine for nothing.
     */
    suspend fun setPreferences(options: RouteOptions): Outcome<RoutePlanState> = revisePreferences(options)

    /** Drops the one-route override and routes with the profile defaults again. */
    suspend fun resetPreferences(): Outcome<RoutePlanState> = revisePreferences(null)

    private suspend fun revisePreferences(override: RouteOptions?): Outcome<RoutePlanState> {
        val snapshot =
            mutex.withLock {
                val currentState = _state.value
                val profileDefaults = optionsForProfile(profile)
                val nextOverride = override?.takeUnless { value -> value == profileDefaults }
                if (currentState.overridePreferences == nextOverride) {
                    return@withLock currentState
                }
                ticket.incrementAndGet()
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
    suspend fun select(index: Int): Outcome<RoutePlanState> {
        val outcome =
            mutex.withLock {
                val current = _state.value
                if (current.status != RoutePlanStatus.Ready || index !in current.options.indices) {
                    return@withLock Outcome.Failure(AppError.InvalidInput)
                }
                setState(current.copy(selectedIndex = index))
                Outcome.Success(_state.value)
            }
        annotateSelected()
        return outcome
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
        val published =
            mutex.withLock {
                if (ticket.get() != mine) {
                    return@withLock Outcome.Failure(AppError.Cancelled)
                }
                when (outcome) {
                    is Outcome.Failure -> {
                        val failure = failureOf(outcome.error)
                        val missing = missingFor(failure, snapshot)
                        setState(
                            _state.value.copy(
                                status = RoutePlanStatus.Failed,
                                options = emptyList(),
                                failure = if (missing == null) failure else RoutePlanFailure.RegionsMissing,
                                missing = missing,
                                missingDownload = null,
                                maxDistanceMeters = (outcome.error as? AppError.TooFar)?.limitMeters,
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
        annotateSelected()
        return published
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
                    runs = route.runs,
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
        val prompting = value.failure == RoutePlanFailure.RegionsMissing
        _state.value =
            value.copy(
                missing = value.missing.takeIf { prompting },
                missingDownload = value.missingDownload.takeIf { prompting },
                undoAvailable = undoStack.isNotEmpty(),
                redoAvailable = redoStack.isNotEmpty(),
            )
    }

    /**
     * Starts the annotate pass for the selected option, if this planner has one
     * (T-0409). The route is already published, so the caller never waits for
     * the engine; the pass writes its answer when it arrives and is discarded
     * when a newer edit or route superseded it.
     */
    private suspend fun annotateSelected() {
        val scope = annotationScope ?: return
        val request = mutex.withLock { claimAnnotation() } ?: return
        scope.launch { runAnnotation(request) }
    }

    /**
     * Marks the selected option as asked for and returns what to annotate. A
     * generation is the unit, so an option is asked for once even when the user
     * re-selects it or the same state is published again.
     */
    private fun claimAnnotation(): AnnotationRequest? {
        val current = _state.value
        if (current.status != RoutePlanStatus.Ready) {
            return null
        }
        val index = current.selectedIndex
        val option = current.options.getOrNull(index) ?: return null
        val indexes = annotatedIndexes.getOrPut(current.generation) { mutableSetOf() }
        if (!indexes.add(index)) {
            return null
        }
        while (annotatedIndexes.size > MAX_ANNOTATED_GENERATIONS) {
            annotatedIndexes.remove(annotatedIndexes.keys.first())
        }
        return AnnotationRequest(
            ticket = ticket.get(),
            generation = current.generation,
            index = index,
            route = option.toRoute(),
        )
    }

    private suspend fun runAnnotation(request: AnnotationRequest) {
        val outcome =
            try {
                annotateRoute(
                    request.route,
                    profile,
                    CancellationHandle { ticket.get() != request.ticket },
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Outcome.Failure(AppError.Internal("route.planner", exception.message))
            }
        applyAnnotation(request, outcome)
    }

    /**
     * Replaces the annotated option's runs, and nothing else. The legs in
     * [RoutePlanOption.segments] stay as published, so guidance keeps one
     * waypoint per leg after annotation. A result for a route the plan no
     * longer shows is dropped, and its claim is released so a restored
     * generation can ask again.
     */
    private suspend fun applyAnnotation(
        request: AnnotationRequest,
        outcome: Outcome<Route>,
    ) {
        mutex.withLock {
            if (ticket.get() != request.ticket) {
                releaseClaim(request)
                return@withLock
            }
            val current = _state.value
            if (current.status != RoutePlanStatus.Ready || current.generation != request.generation) {
                releaseClaim(request)
                return@withLock
            }
            when (outcome) {
                is Outcome.Failure -> {
                    log("route annotation failed: ${outcome.error}")
                }

                is Outcome.Success -> {
                    val option = current.options.getOrNull(request.index) ?: return@withLock
                    if (outcome.value.runs == option.runs) {
                        return@withLock
                    }
                    val options = current.options.toMutableList()
                    options[request.index] = option.copy(runs = outcome.value.runs)
                    setState(current.copy(options = options))
                }
            }
        }
    }

    private fun releaseClaim(request: AnnotationRequest) {
        annotatedIndexes[request.generation]?.remove(request.index)
    }

    private data class AnnotationRequest(
        val ticket: Long,
        val generation: Long,
        val index: Int,
        val route: Route,
    )

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

    /**
     * The regions the stops need and the phone lacks, when a route failed in a
     * way a download can fix (T-0511). Only a failure to find a route or the
     * pack counts: an unusable pack or a broken engine is not cured by more
     * data. The check runs after the failure, never before the request, so a
     * development pack that routes without installed regions is not blocked.
     */
    private fun missingFor(
        failure: RoutePlanFailure,
        snapshot: RoutePlanState,
    ): MissingRegions? =
        if (failure == RoutePlanFailure.NoRoute || failure == RoutePlanFailure.PackMissing) {
            MissingRegionFinder.find(snapshot.stops.map { it.position }, downloads.data.value)
        } else {
            null
        }

    /**
     * Downloads the missing regions the plan is prompting for, rebuilds the
     * routing mount and requests the route again (T-0511). The transfers belong
     * to the region store: clearing or editing the plan stops this wait but
     * leaves them running. Returns once the download ended and the route was
     * asked for, or at once on [annotationScope] when the planner has one, so
     * a command does not outlive its caller's patience.
     */
    suspend fun downloadMissing(): Outcome<RoutePlanState> {
        val started =
            mutex.withLock {
                val current = _state.value
                val prompt = current.missing
                if (current.failure != RoutePlanFailure.RegionsMissing || prompt == null || current.missingDownload?.isRunning == true) {
                    return@withLock null
                }
                setState(current.copy(missingDownload = MissingRegionsDownload(bytesTotal = prompt.bytes)))
                prompt to ticket.get()
            } ?: return Outcome.Failure(AppError.InvalidInput)
        val scope = annotationScope
        if (scope == null) {
            runMissingDownload(started.first, started.second)
        } else {
            scope.launch { runMissingDownload(started.first, started.second) }
        }
        return Outcome.Success(_state.value)
    }

    private suspend fun runMissingDownload(
        prompt: MissingRegions,
        planTicket: Long,
    ) {
        val result =
            MissingRegionsDownloader(downloads, packs).run(prompt) { progress ->
                mutex.withLock {
                    if (ticket.get() == planTicket) setState(_state.value.copy(missingDownload = progress))
                }
            }
        val next =
            mutex.withLock {
                if (ticket.get() != planTicket) return@withLock null
                val current = _state.value
                if (result == MissingDownloadResult.Ready) {
                    ticket.incrementAndGet()
                    setState(
                        current.copy(
                            status = RoutePlanStatus.Planning,
                            failure = null,
                        ),
                    )
                    _state.value
                } else {
                    setState(current.copy(missingDownload = problemOf(result, prompt)))
                    null
                }
            }
        if (next != null) request(next)
    }

    private fun problemOf(
        result: MissingDownloadResult,
        prompt: MissingRegions,
    ): MissingRegionsDownload =
        when (result) {
            is MissingDownloadResult.Failed -> {
                result.progress
            }

            MissingDownloadResult.Stopped -> {
                MissingRegionsDownload(
                    bytesTotal = prompt.bytes,
                    problem = DownloadProblem(DownloadProblemKind.OTHER),
                )
            }

            MissingDownloadResult.MountUnavailable -> {
                MissingRegionsDownload(
                    bytesTotal = prompt.bytes,
                    problem = DownloadProblem(DownloadProblemKind.STORAGE),
                )
            }

            MissingDownloadResult.Ready -> {
                MissingRegionsDownload(bytesTotal = prompt.bytes)
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
            is AppError.PluginDisabled,
            -> {
                RoutePlanFailure.EngineUnavailable
            }

            is AppError.TooFar -> {
                RoutePlanFailure.TooFar
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

        /**
         * How many produced generations keep their annotate claims. Undo can
         * restore an older generation, so a few are kept; each entry is a set
         * of option indexes and no geometry.
         */
        const val MAX_ANNOTATED_GENERATIONS = 8
    }
}
