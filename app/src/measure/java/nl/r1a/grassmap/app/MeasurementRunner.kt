package nl.r1a.grassmap.app

import android.util.Log
import kotlinx.coroutines.flow.filterNotNull
import nl.r1a.grassmap.core.services.MockGpxLocationSource
import nl.r1a.grassmap.navigation.FerrostarRouteFollower
import nl.r1a.grassmap.navigation.NavigationConfig
import nl.r1a.grassmap.navigation.NavigationSession
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine

/** One route-planning measurement pair: the first call and the repeats. */
internal data class RoutePlanTiming(
    val coldMillis: Long,
    val warmMillis: List<Long>,
) {
    /** The median of the warm calls, or null when none succeeded. */
    val warmMedianMillis: Double? = median(warmMillis)
}

/**
 * One reroute replay sample. [millis] is the session's own `reroute_ms`; it is
 * null when the replay produced no reroute, and [error] then says why.
 */
internal data class RerouteSample(
    val millis: Long?,
    val fixCount: Int,
    val offRouteFixCount: Int,
    val detectionMillis: Long?,
    val rerouteCount: Int,
    val error: String? = null,
)

/** Everything the screen measures, before the build, device and pack facts. */
internal data class MeasurementResult(
    val route: Route,
    val routePlan: RoutePlanTiming,
    val rerouteSamples: List<RerouteSample>,
    val warmRuns: Int,
    val rerouteRuns: Int,
    val fixTickMillis: Long,
    val detourMeters: Double,
) {
    val rerouteMedianMillis: Double? =
        median(rerouteSamples.mapNotNull { sample -> sample.millis })
}

/**
 * Runs the three numbers the phone owes: cold route planning, warm route
 * planning, and the reroute replay through the real [NavigationSession].
 *
 * The runner owns no Android state. It takes the engine resolver, so the
 * screen decides when the process is fresh and the timings stay honest.
 */
internal class MeasurementRunner(
    private val engineProvider: suspend () -> RoutingEngine?,
    private val request: RouteRequest,
    private val profile: RouteProfile,
    private val config: NavigationConfig = NavigationConfig(),
    private val warmRuns: Int = WARM_RUNS,
    private val rerouteRuns: Int = REROUTE_RUNS,
    private val fixTickMillis: Long = FIX_TICK_MILLIS,
    private val detourMeters: Double = NavReplay.DEFAULT_DETOUR_METERS,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val log: (String) -> Unit = { message -> Log.i(TAG, message) },
) {
    /**
     * Runs every measurement in order and reports progress before each step.
     * A failed route stops the run; a reroute sample that produced no reroute
     * is kept with its error instead of being dropped.
     */
    suspend fun run(onProgress: (String) -> Unit): Result<MeasurementResult> {
        onProgress("Route planning: cold")
        val engine = engineProvider() ?: return Result.failure(IllegalStateException("no routing engine"))
        val coldStart = clockMillis()
        val cold = engine.route(request, CancellationHandle.None)
        val coldMillis = clockMillis() - coldStart
        val route =
            when (cold) {
                is Outcome.Failure -> {
                    return Result.failure(IllegalStateException(cold.error.toString()))
                }

                is Outcome.Success -> {
                    cold.value.firstOrNull()
                        ?: return Result.failure(IllegalStateException("the engine returned no route"))
                }
            }
        log("measure-route-cold ms=$coldMillis distance_m=${route.distanceMeters} points=${route.geometry.size}")

        val warm = mutableListOf<Long>()
        for (run in 1..warmRuns) {
            onProgress("Route planning: warm $run/$warmRuns")
            val started = clockMillis()
            val outcome = engine.route(request, CancellationHandle.None)
            val elapsed = clockMillis() - started
            if (outcome is Outcome.Failure) {
                return Result.failure(IllegalStateException("warm route $run failed: ${outcome.error}"))
            }
            warm += elapsed
            log("measure-route-warm run=$run ms=$elapsed")
        }

        val samples = mutableListOf<RerouteSample>()
        for (run in 1..rerouteRuns) {
            onProgress("Reroute replay: $run/$rerouteRuns")
            samples += rerouteSample(engine, route)
        }
        val result =
            MeasurementResult(
                route = route,
                routePlan = RoutePlanTiming(coldMillis, warm),
                rerouteSamples = samples,
                warmRuns = warmRuns,
                rerouteRuns = rerouteRuns,
                fixTickMillis = fixTickMillis,
                detourMeters = detourMeters,
            )
        log(
            "measure-done cold_ms=$coldMillis warm_median_ms=${result.routePlan.warmMedianMillis} " +
                "reroute_median_ms=${result.rerouteMedianMillis}",
        )
        return Result.success(result)
    }

    private suspend fun rerouteSample(
        engine: RoutingEngine,
        route: Route,
    ): RerouteSample {
        val replay =
            NavReplay.gpx(route, detourMeters, config.speedMetersPerSecond(profile))
                ?: return RerouteSample(null, 0, 0, null, 0, "the route is too short to replay")
        val source =
            when (val parsed = MockGpxLocationSource.fromGpx(replay, fixTickMillis)) {
                is Outcome.Failure -> return RerouteSample(null, 0, 0, null, 0, parsed.error.toString())
                is Outcome.Success -> parsed.value
            }
        val session =
            NavigationSession(
                follower = FerrostarRouteFollower(config = config, log = log),
                routing = engine,
                profile = profile,
                config = config,
                clockMillis = clockMillis,
                log = log,
            )
        return when (val run = session.run(route, source.fixes.filterNotNull())) {
            is Outcome.Failure -> {
                RerouteSample(null, 0, 0, null, 0, run.error.toString())
            }

            is Outcome.Success -> {
                RerouteSample(
                    millis = run.value.lastRerouteMillis,
                    fixCount = run.value.fixCount,
                    offRouteFixCount = run.value.offRouteFixCount,
                    detectionMillis = run.value.detectionMillis,
                    rerouteCount = run.value.rerouteCount,
                    error = if (run.value.lastRerouteMillis == null) "the replay produced no reroute" else null,
                )
            }
        }
    }

    private companion object {
        const val TAG = "GrassMapsMeasure"

        /** Warm route calls per run; the median of this many is the warm number. */
        const val WARM_RUNS = 10

        /** Reroute replays per run; the median of this many is the reroute number. */
        const val REROUTE_RUNS = 5

        /**
         * Wall pacing between replay fixes. The fixes carry their own
         * distance-based timestamps, so this only shortens the run: it never
         * changes the 22 m gate or the measured reroute time.
         */
        const val FIX_TICK_MILLIS = 100L
    }
}

/** The median of the samples, or null when there is nothing to average. */
internal fun median(values: List<Long>): Double? {
    if (values.isEmpty()) {
        return null
    }
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[middle].toDouble()
    } else {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}

/** Rounds the median so the report does not imply more precision than it has. */
internal fun Double?.oneDecimal(): Double? = this?.let { value -> Math.round(value * 10.0) / 10.0 }
