package nl.r1a.grassmap.app

import android.util.Log
import kotlinx.coroutines.flow.filterNotNull
import nl.r1a.grassmap.core.services.MockGpxLocationSource
import nl.r1a.grassmap.navigation.FerrostarRouteFollower
import nl.r1a.grassmap.navigation.NavigationConfig
import nl.r1a.grassmap.navigation.NavigationSession
import nl.r1a.grassmap.navigation.RerouteEvent
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine

/**
 * One profile's route-planning measurements. [coldMillis] is null for a profile
 * that ran after the engine was already mounted by an earlier profile; only the
 * first profile of a process has a cold number.
 */
internal data class RoutePlanTiming(
    val coldMillis: Long?,
    val warmMillis: List<Long>,
) {
    /** The median of the warm calls, or null when none succeeded. */
    val warmMedianMillis: Double? = median(warmMillis)
}

/**
 * One reroute replay. [events] holds every reroute the session installed, in
 * order, with its own fix indices; the report never squeezes them into one
 * number without saying which one it is.
 */
internal data class RerouteRun(
    val fixCount: Int,
    val offRouteFixCount: Int,
    val detectionMillis: Long?,
    val events: List<RerouteEvent>,
    val error: String? = null,
) {
    val firstRerouteMillis: Long? = events.firstOrNull()?.durationMillis
}

/** One profile's route and reroute numbers. */
internal data class ProfileMeasurement(
    val profile: RouteProfile,
    val gateMeters: Double,
    val route: Route,
    val routePlan: RoutePlanTiming,
    val rerouteRuns: List<RerouteRun>,
) {
    /** Median across runs of each run's first reroute; the acceptance number. */
    val firstRerouteMillis: Double? = median(rerouteRuns.mapNotNull { it.firstRerouteMillis })

    /** Median of every installed reroute in every run, pooled. */
    val allReroutesMedianMillis: Double? =
        median(rerouteRuns.flatMap { run -> run.events }.map { event -> event.durationMillis })
}

/** Everything the screen measures, before the build, device and pack facts. */
internal data class MeasurementResult(
    val profiles: List<ProfileMeasurement>,
    val warmRuns: Int,
    val rerouteRuns: Int,
    val fixTickMillis: Long,
    val detourMeters: Double,
)

/**
 * Runs the numbers the phone owes: cold route planning (the first profile
 * only), warm route planning, and the reroute replay through the real
 * [NavigationSession], for the walk and bike profiles.
 *
 * The runner owns no Android state. It takes the engine resolver, so the
 * screen decides when the process is fresh and the timings stay honest.
 */
internal class MeasurementRunner(
    private val engineProvider: suspend () -> RoutingEngine?,
    private val waypoints: List<GeoPoint>,
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
     * Walk runs first so its cold number is the engine's first mount; bike runs
     * second and has no cold number, which the report states.
     */
    suspend fun run(onProgress: (String) -> Unit): Result<MeasurementResult> =
        try {
            Result.success(runProfiles(onProgress))
        } catch (error: Exception) {
            Result.failure(error)
        }

    private suspend fun runProfiles(onProgress: (String) -> Unit): MeasurementResult {
        val engine = engineProvider() ?: throw IllegalStateException("no routing engine")
        val walk = measureProfile(engine, RouteProfile.Walk, cold = true, onProgress)
        val bike = measureProfile(engine, RouteProfile.Bike, cold = false, onProgress)
        log(
            "measure-done walk_first_reroute_ms=${walk.firstRerouteMillis} " +
                "bike_first_reroute_ms=${bike.firstRerouteMillis}",
        )
        return MeasurementResult(
            profiles = listOf(walk, bike),
            warmRuns = warmRuns,
            rerouteRuns = rerouteRuns,
            fixTickMillis = fixTickMillis,
            detourMeters = detourMeters,
        )
    }

    private suspend fun measureProfile(
        engine: RoutingEngine,
        profile: RouteProfile,
        cold: Boolean,
        onProgress: (String) -> Unit,
    ): ProfileMeasurement {
        val request = RouteRequest(waypoints = waypoints, profile = profile)
        var route: Route? = null
        val coldMillis =
            if (cold) {
                onProgress("${profile.label()} route planning: cold")
                val started = clockMillis()
                val outcome = engine.route(request, CancellationHandle.None)
                val elapsed = clockMillis() - started
                val measured = routeOf(outcome, "cold")
                route = measured
                log("measure-route-cold profile=$profile ms=$elapsed points=${measured.geometry.size}")
                elapsed
            } else {
                null
            }

        val warm = mutableListOf<Long>()
        for (run in 1..warmRuns) {
            onProgress("${profile.label()} route planning: warm $run/$warmRuns")
            val started = clockMillis()
            val outcome = engine.route(request, CancellationHandle.None)
            val elapsed = clockMillis() - started
            route = routeOf(outcome, "warm $run")
            warm += elapsed
            log("measure-route-warm profile=$profile run=$run ms=$elapsed")
        }

        val measuredRoute = route ?: throw IllegalStateException("$profile produced no route")

        val runs = mutableListOf<RerouteRun>()
        for (run in 1..rerouteRuns) {
            onProgress("${profile.label()} reroute replay: $run/$rerouteRuns")
            runs += rerouteRun(engine, measuredRoute, profile)
        }
        return ProfileMeasurement(
            profile = profile,
            gateMeters = config.travelMeters(profile),
            route = measuredRoute,
            routePlan = RoutePlanTiming(coldMillis, warm),
            rerouteRuns = runs,
        )
    }

    private fun routeOf(
        outcome: Outcome<List<Route>>,
        step: String,
    ): Route =
        when (outcome) {
            is Outcome.Failure -> {
                throw IllegalStateException("$step route failed: ${outcome.error}")
            }

            is Outcome.Success -> {
                outcome.value.firstOrNull()
                    ?: throw IllegalStateException("$step returned no route")
            }
        }

    private suspend fun rerouteRun(
        engine: RoutingEngine,
        route: Route,
        profile: RouteProfile,
    ): RerouteRun {
        val replay =
            NavReplay.gpx(route, detourMeters, config.speedMetersPerSecond(profile))
                ?: return RerouteRun(0, 0, null, emptyList(), "the route is too short to replay")
        val source =
            when (val parsed = MockGpxLocationSource.fromGpx(replay, fixTickMillis)) {
                is Outcome.Failure -> return RerouteRun(0, 0, null, emptyList(), parsed.error.toString())
                is Outcome.Success -> parsed.value
            }
        val events = mutableListOf<RerouteEvent>()
        val session =
            NavigationSession(
                follower = FerrostarRouteFollower(config = config, log = log),
                routing = engine,
                profile = profile,
                config = config,
                clockMillis = clockMillis,
                log = log,
                onReroute = { event -> events += event },
            )
        return when (val run = session.run(route, source.fixes.filterNotNull())) {
            is Outcome.Failure -> {
                RerouteRun(0, 0, null, emptyList(), run.error.toString())
            }

            is Outcome.Success -> {
                RerouteRun(
                    fixCount = run.value.fixCount,
                    offRouteFixCount = run.value.offRouteFixCount,
                    detectionMillis = run.value.detectionMillis,
                    events = events.toList(),
                    error = if (events.isEmpty()) "the replay produced no reroute" else null,
                )
            }
        }
    }

    private fun RouteProfile.label(): String = name.lowercase()

    private companion object {
        const val TAG = "GrassMapsMeasure"

        /** Warm route calls per profile; the median of this many is the warm number. */
        const val WARM_RUNS = 10

        /** Reroute replays per profile; the first reroute of each is the headline. */
        const val REROUTE_RUNS = 5

        /**
         * Wall pacing between replay fixes. The fixes carry their own
         * distance-based timestamps, so this only shortens the run: it never
         * changes the profile's gate or the measured reroute time.
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
