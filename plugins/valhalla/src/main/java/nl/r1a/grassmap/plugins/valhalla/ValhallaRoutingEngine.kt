package nl.r1a.grassmap.plugins.valhalla

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.ElevationAccess
import nl.r1a.grassmap.plugin.api.host.NoElevationAccess
import nl.r1a.grassmap.plugin.api.host.RoutingPackAccess
import nl.r1a.grassmap.plugin.api.model.AttributeValue
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.ProviderContract
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteAttributes
import nl.r1a.grassmap.plugin.api.provider.RouteModifierKind
import nl.r1a.grassmap.plugin.api.provider.RouteModifierRules
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.provider.RouteSegment
import nl.r1a.grassmap.plugin.api.provider.RoutingCapabilities
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine
import nl.r1a.grassmap.plugin.api.provider.RoutingEngineContract
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
    )

    private val lock = Any()
    private var transport: ValhallaTransport? = null
    private var closed = false

    // valhalla-mobile does not document thread safety for route(), and one
    // native engine serves the process. Calls are serialised rather than
    // risking native state corruption under concurrent reroutes.
    private val routeMutex = Mutex()

    override val contract: ProviderContract = RoutingEngineContract.REFERENCE

    override fun capabilities(): RoutingCapabilities =
        RoutingCapabilities(
            profiles = setOf(RouteProfile.Walk, RouteProfile.Bike, RouteProfile.Hike),
            modifierKinds =
                setOf(
                    RouteModifierKind.Multiplier,
                    RouteModifierKind.AddedSeconds,
                    RouteModifierKind.Forbidden,
                ),
            maxAlternatives = 2,
            maxDistanceMeters = 250_000.0,
            attributeKeys = RouteAttributes.V1,
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

            val raw =
                routeMutex.withLock {
                    try {
                        active.route(ValhallaRequest.build(request))
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: ValhallaPackException) {
                        return@withContext packFailure(exception)
                    } catch (exception: Exception) {
                        log.info("route failed: ${exception.message}")
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
     * Re-splits the route by graph edge and adds the five v1 attributes.
     *
     * Four come straight from `trace_attributes`. Grade does not: the phase-A
     * pack was built without elevation, so Valhalla reports the "no data"
     * sentinel (-500) for every edge. Grade is instead sampled from the mounted
     * region DEM through [ElevationAccess]; without one the key is absent
     * rather than wrong.
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
                route.copy(segments = grade(ValhallaTrace.segments(route, edges), route.geometry)),
            )
        }

    private suspend fun grade(
        segments: List<RouteSegment>,
        points: List<GeoPoint>,
    ): List<RouteSegment> {
        val heights = HashMap<Int, Double?>()
        return segments.map { segment ->
            val start = heightAt(points, heights, segment.startIndex)
            val end = heightAt(points, heights, segment.endIndex)
            if (start == null || end == null || segment.distanceMeters <= 0.0) {
                segment
            } else {
                val grade = (end - start) / segment.distanceMeters * 100.0
                segment.copy(
                    attributes = segment.attributes + (RouteAttributes.GRADE to AttributeValue.NumberValue(grade)),
                )
            }
        }
    }

    private suspend fun heightAt(
        points: List<GeoPoint>,
        cache: MutableMap<Int, Double?>,
        index: Int,
    ): Double? {
        if (cache.containsKey(index)) {
            return cache[index]
        }
        val point = points.getOrNull(index) ?: return null
        val height = (elevation.elevationMeters(point) as? Outcome.Success)?.value
        cache[index] = height
        return height
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            transport?.let { active ->
                runCatching { active.close() }
            }
            transport = null
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

    private fun transportOrCreate(): ValhallaTransport =
        synchronized(lock) {
            check(!closed) { "valhalla engine is closed" }
            transport ?: transportFactory().also { transport = it }
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
        if (straightLineMetres(request) > capabilities.maxDistanceMeters) {
            return AppError.InvalidInput
        }
        return null
    }

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
        const val MIN_WAYPOINTS = 2
        const val EARTH_RADIUS_METRES = 6_371_000.0
    }
}
