package nl.r1a.grassmaps.plugins.valhalla

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.r1a.grassmaps.plugin.api.contract.CancellationHandle
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.host.ElevationAccess
import nl.r1a.grassmaps.plugin.api.host.NoElevationAccess
import nl.r1a.grassmaps.plugin.api.host.RoutingPackAccess
import nl.r1a.grassmaps.plugin.api.model.AttributeValue
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.provider.ProviderContract
import nl.r1a.grassmaps.plugin.api.provider.Route
import nl.r1a.grassmaps.plugin.api.provider.RouteAttributes
import nl.r1a.grassmaps.plugin.api.provider.RouteModifierKind
import nl.r1a.grassmaps.plugin.api.provider.RouteModifierRules
import nl.r1a.grassmaps.plugin.api.provider.RouteProfile
import nl.r1a.grassmaps.plugin.api.provider.RouteRequest
import nl.r1a.grassmaps.plugin.api.provider.RouteSegment
import nl.r1a.grassmaps.plugin.api.provider.RoutingCapabilities
import nl.r1a.grassmaps.plugin.api.provider.RoutingEngine
import nl.r1a.grassmaps.plugin.api.provider.RoutingEngineContract
import java.io.Closeable
import java.io.File

/**
 * The v1 default engine: Valhalla through valhalla-mobile.
 *
 * The engine is created on the first route, because construction opens and
 * mmaps the tile extract. One instance serves the process; [close] frees the
 * native actor. Config files are written beside the pack, never inside it.
 */
class ValhallaRoutingEngine internal constructor(
    private val transportFactory: () -> ValhallaTransport,
    private val log: RoutingLog = RoutingLog.None,
    private val elevation: ElevationAccess = NoElevationAccess,
    private val packKey: () -> String? = { null },
) : RoutingEngine,
    Closeable {
    /** A platform engine over the pack copied to the device. */
    constructor(
        packDirectory: File,
        configDirectory: File,
        log: RoutingLog = RoutingLog.None,
        elevation: ElevationAccess = NoElevationAccess,
    ) : this({ NativeValhallaTransport(packDirectory, configDirectory) }, log, elevation)

    /**
     * The engine a plugin builds: the pack directory comes from the host at
     * route time, so activation succeeds before a pack is mounted and a route
     * reports `NotFound` until one is.
     */
    constructor(
        packs: RoutingPackAccess,
        log: RoutingLog = RoutingLog.None,
        elevation: ElevationAccess = NoElevationAccess,
    ) : this(
        {
            val pack = ValhallaPack.inspect(packs.packDirectory()).readyOrThrow()
            NativeValhallaTransport(pack.directory, pack.directory.parentFile ?: pack.directory)
        },
        log,
        elevation,
        { packs.packDirectory() },
    )

    private val lock = Any()
    private var transport: ValhallaTransport? = null
    private var openedKey: String? = null
    private var closed = false

    // valhalla-mobile does not document thread safety for route(), and one
    // native engine serves the process. Calls are serialised rather than
    // risking native state corruption under concurrent reroutes.
    private val routeMutex = Mutex()

    override val contract: ProviderContract = RoutingEngineContract.REFERENCE

    override fun capabilities(): RoutingCapabilities =
        RoutingCapabilities(
            profiles = SUPPORTED_PROFILES,
            modifierKinds =
                setOf(
                    RouteModifierKind.Multiplier,
                    RouteModifierKind.AddedSeconds,
                    RouteModifierKind.Forbidden,
                ),
            maxAlternatives = 2,
            maxDistanceMeters = SUPPORTED_PROFILES.maxOf(ValhallaProfiles::maxDistanceMeters),
            maxDistanceMetersByProfile = SUPPORTED_PROFILES.associateWith(ValhallaProfiles::maxDistanceMeters),
            // Access is in the shared vocabulary but has no Valhalla source:
            // the trace does not carry an OSM access tag, so declaring it
            // would promise a key the engine never fills.
            attributeKeys = RouteAttributes.V1 - RouteAttributes.ACCESS,
            // The pack's loki actions list `trace_route`, and valhalla-mobile
            // 0.6.4 exposes `traceRouteRaw`; both are needed for map matching.
            hasMatchTrack = true,
        )

    override suspend fun route(
        request: RouteRequest,
        cancellation: CancellationHandle,
    ): Outcome<List<Route>> =
        withContext(Dispatchers.IO) {
            invalidReason(request)?.let { return@withContext Outcome.Failure(it) }
            if (cancellation.isCancelled()) {
                return@withContext Outcome.Failure(AppError.Cancelled)
            }

            val raw =
                routeMutex.withLock {
                    val active =
                        try {
                            transportOrCreate()
                        } catch (exception: ValhallaPackException) {
                            return@withContext packFailure(exception)
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            return@withContext internalFailure("routing.valhalla.engine", exception)
                        }
                    try {
                        active.route(ValhallaRequest.build(request))
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: ValhallaPackException) {
                        return@withContext packFailure(exception)
                    } catch (exception: Exception) {
                        log.info("route failed: ${exception.message}")
                        if (isDistanceLimit(exception)) {
                            return@withContext tooFar(request)
                        }
                        if (!hasCostOverlay(request) || !isOffGraphOverlay(exception)) {
                            return@withContext internalFailure("routing.valhalla.route", exception)
                        }
                        log.info("cost line has no edges near it; using exclusion polygons")
                        try {
                            active.route(
                                ValhallaRequest.build(request, useExclusionForModifiers = true),
                            )
                        } catch (retry: CancellationException) {
                            throw retry
                        } catch (retry: ValhallaPackException) {
                            return@withContext packFailure(retry)
                        } catch (retry: Exception) {
                            log.info("exclusion fallback failed: ${retry.message}")
                            if (isDistanceLimit(retry)) {
                                return@withContext tooFar(request)
                            }
                            return@withContext internalFailure("routing.valhalla.route", retry)
                        }
                    }
                }
            if (cancellation.isCancelled()) {
                return@withContext Outcome.Failure(AppError.Cancelled)
            }
            ValhallaResponse.parse(raw)
        }

    /**
     * Re-splits the route's line by graph edge and adds the v1 attributes to
     * the resulting runs. The legs in [Route.segments] are left as they are: a
     * reroute reads one waypoint per leg, so an annotated route must not
     * pretend to have one leg per edge.
     *
     * Three attributes come straight from `trace_attributes`. Grade does not:
     * the phase-A pack was built without elevation, so Valhalla reports the "no
     * data" sentinel (-500) for every edge. Grade is instead sampled from the
     * mounted region DEM through [ElevationAccess] over [GRADE_WINDOW_METERS] of
     * route, so one short edge does not turn a step into a cliff; without a DEM
     * the key is absent rather than wrong.
     *
     * `access` is absent on purpose. Valhalla's trace does not carry the way's
     * OSM access tag, and a route Valhalla chose is passable for the profile by
     * construction, so any value here would be invented. The private-way
     * warning therefore has no source in v1; see `test-data/routing/README.md`.
     */
    override suspend fun annotate(
        route: Route,
        profile: RouteProfile,
        cancellation: CancellationHandle,
    ): Outcome<Route> =
        withContext(Dispatchers.IO) {
            val chunks = ValhallaTrace.chunks(route.geometry)
            if (chunks.isEmpty()) {
                return@withContext Outcome.Failure(AppError.InvalidInput)
            }
            if (cancellation.isCancelled()) {
                return@withContext Outcome.Failure(AppError.Cancelled)
            }
            val active =
                try {
                    transportOrCreate()
                } catch (exception: ValhallaPackException) {
                    return@withContext packFailure(exception)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    return@withContext internalFailure("routing.valhalla.engine", exception)
                }
            val profileOptions = ValhallaProfiles.of(profile)
            val edges = mutableListOf<ValhallaTrace.TraceEdge>()
            routeMutex.withLock {
                val active =
                    try {
                        transportOrCreate()
                    } catch (exception: ValhallaPackException) {
                        return@withContext packFailure(exception)
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        return@withContext internalFailure("routing.valhalla.engine", exception)
                    }
                for (chunk in chunks) {
                    if (cancellation.isCancelled()) {
                        return@withContext Outcome.Failure(AppError.Cancelled)
                    }
                    val raw =
                        try {
                            active.traceAttributes(ValhallaTrace.request(chunk, profileOptions))
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: ValhallaPackException) {
                            return@withContext packFailure(exception)
                        } catch (exception: Exception) {
                            log.info("trace_attributes failed: ${exception.message}")
                            return@withContext internalFailure("routing.valhalla.trace", exception)
                        }
                    when (val parsed = ValhallaTrace.edges(raw)) {
                        is Outcome.Failure -> return@withContext parsed
                        is Outcome.Success -> edges += parsed.value
                    }
                }
            }
            Outcome.Success(
                route.copy(runs = grade(route, ValhallaTrace.segments(route, edges))),
            )
        }

    /**
     * Grades each run over a window of at least [GRADE_WINDOW_METERS] of route
     * centred on it. A graph edge shorter than the window borrows the stretch
     * around it instead of reporting the height change between its own two
     * ends: a 10 m edge over a 2 m step reads the step over 50 m, not 20 %.
     */
    private suspend fun grade(
        route: Route,
        segments: List<RouteSegment>,
    ): List<RouteSegment> {
        val line = RouteLine(route.geometry)
        if (line.totalMeters <= 0.0) {
            return segments
        }
        return segments.map { segment ->
            val window = line.window(segment.startIndex, segment.endIndex)
            val start = heightAt(line.pointAt(window.startMeters))
            val end = heightAt(line.pointAt(window.endMeters))
            val length = window.endMeters - window.startMeters
            if (start == null || end == null || length <= 0.0) {
                segment
            } else {
                val grade = (end - start) / length * 100.0
                segment.copy(
                    attributes = segment.attributes + (RouteAttributes.GRADE to AttributeValue.NumberValue(grade)),
                )
            }
        }
    }

    private suspend fun heightAt(point: GeoPoint): Double? = (elevation.elevationMeters(point) as? Outcome.Success)?.value

    /** The route line as a distance scale, for placing a grade window's ends. */
    private class RouteLine(
        private val points: List<GeoPoint>,
    ) {
        private val cumulative = DoubleArray(points.size)

        init {
            for (index in 1 until points.size) {
                cumulative[index] = cumulative[index - 1] + ValhallaRequest.distanceMetres(points[index - 1], points[index])
            }
        }

        val totalMeters: Double
            get() = cumulative.lastOrNull() ?: 0.0

        /** The window around the run from [startIndex] to [endIndex], clipped to the line. */
        fun window(
            startIndex: Int,
            endIndex: Int,
        ): Window {
            val start = cumulative[indexOf(startIndex)]
            val end = cumulative[indexOf(endIndex)]
            val length = maxOf(GRADE_WINDOW_METERS, end - start)
            var windowStart = (start + end) / 2.0 - length / 2.0
            windowStart = windowStart.coerceIn(0.0, (totalMeters - length).coerceAtLeast(0.0))
            return Window(windowStart, (windowStart + length).coerceAtMost(totalMeters))
        }

        /** The point at [distance] metres from the line's start, between vertices when needed. */
        fun pointAt(distance: Double): GeoPoint {
            if (points.size < 2) {
                return points.first()
            }
            val bounded = distance.coerceIn(0.0, totalMeters)
            var low = 0
            var high = cumulative.lastIndex
            while (low < high) {
                val middle = (low + high) / 2
                if (cumulative[middle] < bounded) low = middle + 1 else high = middle
            }
            val index = low.coerceAtLeast(1)
            val before = cumulative[index - 1]
            val span = cumulative[index] - before
            val fraction = if (span <= 0.0) 0.0 else ((bounded - before) / span).coerceIn(0.0, 1.0)
            return GeoPoint(
                latitude = points[index - 1].latitude + fraction * (points[index].latitude - points[index - 1].latitude),
                longitude = points[index - 1].longitude + fraction * (points[index].longitude - points[index - 1].longitude),
            )
        }

        private fun indexOf(index: Int): Int = index.coerceIn(0, cumulative.lastIndex)

        data class Window(
            val startMeters: Double,
            val endMeters: Double,
        )
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            transport?.let { active ->
                runCatching { active.close() }
            }
            transport = null
            openedKey = null
        }
    }

    /** One pack problem, logged once and reported as the typed outcome. */
    private fun packFailure(exception: ValhallaPackException): Outcome.Failure {
        log.error("valhalla pack: ${exception.detail}", exception)
        return Outcome.Failure(
            AppError.RoutingPackUnavailable(
                reason =
                    when (exception.problem) {
                        ValhallaPackProblem.Absent -> AppError.RoutingPackUnavailable.Reason.Absent
                        ValhallaPackProblem.Incomplete -> AppError.RoutingPackUnavailable.Reason.Incomplete
                        ValhallaPackProblem.Unusable -> AppError.RoutingPackUnavailable.Reason.Unusable
                    },
                detail = exception.detail,
            ),
        )
    }

    /** An unexpected engine failure: always logged, then reported as internal. */
    private fun internalFailure(
        id: String,
        exception: Exception,
    ): Outcome.Failure {
        log.error("$id failed", exception)
        return Outcome.Failure(AppError.Internal(id, exception.message))
    }

    /**
     * The open transport, or a new one when the host mounted a different pack
     * since it was opened (T-0511). Valhalla reads its tile directory once, so
     * a download that changes the installed set only shows after a reopen. The
     * mount directory's name carries the installed set, so a different name
     * means different tiles. Callers hold [routeMutex], so the old transport
     * is idle when it closes. A host with no pack directory keeps the transport
     * it has.
     */
    private fun transportOrCreate(): ValhallaTransport =
        synchronized(lock) {
            check(!closed) { "valhalla engine is closed" }
            val key = packKey()
            val current = transport
            if (current != null && (key == null || key == openedKey)) {
                return@synchronized current
            }
            if (current != null) {
                log.info("routing pack changed; reopening the engine")
                runCatching { current.close() }
                transport = null
            }
            transportFactory().also {
                transport = it
                openedKey = key
            }
        }

    private fun invalidReason(request: RouteRequest): AppError? {
        val capabilities = capabilities()
        if (request.waypoints.size < MIN_WAYPOINTS) {
            return AppError.InvalidInput
        }
        if (request.profile !in capabilities.profiles) {
            return AppError.Unsupported
        }
        if (request.alternatives !in 0..capabilities.maxAlternatives) {
            return AppError.InvalidInput
        }
        for (modifier in request.modifiers) {
            RouteModifierRules.violationOf(modifier)?.let { return it }
            if (modifier.kind !in capabilities.modifierKinds) {
                return AppError.Unsupported
            }
        }
        val limit = capabilities.maxDistanceFor(request.profile)
        if (straightLineMetres(request) > limit) {
            return AppError.TooFar(limit)
        }
        return null
    }

    private fun isDistanceLimit(exception: Exception): Boolean =
        ERROR_CODE_PATTERN
            .find(exception.message.orEmpty())
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull() == DISTANCE_LIMIT_CODE

    private fun tooFar(request: RouteRequest): Outcome.Failure =
        Outcome.Failure(AppError.TooFar(capabilities().maxDistanceFor(request.profile)))

    private fun hasCostOverlay(request: RouteRequest): Boolean = request.modifiers.any { it.multiplier != null || it.addedSeconds != null }

    private fun isOffGraphOverlay(exception: Exception): Boolean {
        val message = exception.message.orEmpty()
        return message.contains("No edges near", ignoreCase = true) ||
            message.contains("edge walk", ignoreCase = true) ||
            message.contains("code=173") ||
            message.contains("code=233")
    }

    private fun straightLineMetres(request: RouteRequest): Double =
        request.waypoints
            .zipWithNext()
            .sumOf { (from, to) ->
                val latitudeDelta = Math.toRadians(to.latitude - from.latitude)
                val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
                val meanLatitude = Math.toRadians((from.latitude + to.latitude) / 2.0)
                val east = longitudeDelta * kotlin.math.cos(meanLatitude)
                kotlin.math.hypot(latitudeDelta, east) * EARTH_RADIUS_METRES
            }

    private companion object {
        val SUPPORTED_PROFILES = setOf(RouteProfile.Walk, RouteProfile.Bike, RouteProfile.Hike)

        /**
         * Valhalla's error code for a path over the costing's `max_distance`
         * (loki, "Path distance exceeds the max distance limit"). The native
         * wrapper reports it in the exception text as `ValhallaError(code=154, ...)`.
         */
        const val DISTANCE_LIMIT_CODE = 154
        val ERROR_CODE_PATTERN = Regex("code=(\\d+)")
        const val MIN_WAYPOINTS = 2
        const val EARTH_RADIUS_METRES = 6_371_000.0

        /**
         * The shortest stretch a grade is read over. It is longer than a typical
         * graph edge on a footway and short enough that a genuine steep stretch
         * still reads steep.
         */
        const val GRADE_WINDOW_METERS = 50.0
    }
}
