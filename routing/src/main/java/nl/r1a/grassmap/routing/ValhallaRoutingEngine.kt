package nl.r1a.grassmap.routing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.provider.ProviderContract
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteModifierKind
import nl.r1a.grassmap.plugin.api.provider.RouteModifierRules
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
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
) : RoutingEngine,
    Closeable {
    /** A platform engine over the pack copied to the device. */
    constructor(
        packDirectory: File,
        configDirectory: File,
        log: RoutingLog = RoutingLog.None,
    ) : this({ NativeValhallaTransport(packDirectory, configDirectory) }, log)

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
            profiles = setOf(RouteProfile.Walk, RouteProfile.Bike, RouteProfile.Hike, RouteProfile.Car),
            modifierKinds =
                setOf(
                    RouteModifierKind.Multiplier,
                    RouteModifierKind.AddedSeconds,
                    RouteModifierKind.Forbidden,
                ),
            maxAlternatives = 2,
            maxDistanceMeters = 250_000.0,
            attributeKeys = emptySet(),
            hasMatchTrack = false,
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
                } catch (exception: ValhallaPackMissingException) {
                    return@withContext Outcome.Failure(AppError.NotFound)
                } catch (exception: ValhallaPackCorruptException) {
                    return@withContext Outcome.Failure(AppError.CorruptData)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    return@withContext Outcome.Failure(
                        AppError.Internal("routing.valhalla.engine", exception.message),
                    )
                }

            val raw =
                routeMutex.withLock {
                    try {
                        active.route(ValhallaRequest.build(request))
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        log.info("route failed: ${exception.message}")
                        if (!hasCostOverlay(request) || !isOffGraphOverlay(exception)) {
                            return@withContext Outcome.Failure(
                                AppError.Internal("routing.valhalla.route", exception.message),
                            )
                        }
                        log.info("cost line has no edges near it; using exclusion polygons")
                        try {
                            active.route(
                                ValhallaRequest.build(request, useExclusionForModifiers = true),
                            )
                        } catch (retry: CancellationException) {
                            throw retry
                        } catch (retry: Exception) {
                            log.info("exclusion fallback failed: ${retry.message}")
                            return@withContext Outcome.Failure(
                                AppError.Internal("routing.valhalla.route", retry.message),
                            )
                        }
                    }
                }
            if (cancellation.isCancelled()) {
                return@withContext Outcome.Failure(AppError.Cancelled)
            }
            ValhallaResponse.parse(raw)
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
