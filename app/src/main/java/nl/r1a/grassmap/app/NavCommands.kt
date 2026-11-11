package nl.r1a.grassmap.app

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import nl.r1a.grassmap.core.services.MockGpxLocationSource
import nl.r1a.grassmap.navigation.FerrostarRouteFollower
import nl.r1a.grassmap.navigation.NavigationConfig
import nl.r1a.grassmap.navigation.NavigationSession
import nl.r1a.grassmap.navigation.RouteFollowerEngine
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.error.onFailure
import nl.r1a.grassmap.plugin.api.host.MapHost
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine
import java.time.Instant
import kotlin.math.cos

/** The debug trigger that proves the navigation seam on a replay. */
internal val NAV_TEST_COMMAND: CommandId = CommandId("core.nav.test_follow")

private const val TAG = "GrassMapsNav"
private const val AREA_FIXTURE = "fixture"
private const val AREA_LVIV = "lviv"
private const val DEFAULT_TICK_MILLIS = 500L
private const val DEFAULT_DETOUR_METERS = 45.0
private const val DETOUR_TAPER_FIXES = 3.0
private const val GPX_BASE_EPOCH_MILLIS = 1_767_225_600_000L

/**
 * Routes a walk, builds a GPX replay around that exact line with a deliberate
 * off-route segment, and follows it with the real navigation session.
 *
 * The replay is generated from the returned geometry, not committed, so the
 * detour is guaranteed to leave the route whatever the routing pack says. It
 * still goes through [MockGpxLocationSource], so the T-0022 GPX parser and the
 * one-fix-per-tick replay are the ones exercised. The command logs on-route,
 * off-route, reroute and arrival lines and returns the measured milliseconds.
 */
internal class TestFollowCommand(
    private val session: MapSessionHolder,
    private val engine: suspend () -> RoutingEngine?,
    private val log: (String) -> Unit = { message -> Log.i(TAG, message) },
    private val followerFactory: () -> RouteFollowerEngine = { FerrostarRouteFollower(log = log) },
    private val config: NavigationConfig = NavigationConfig(),
    private val tickMillis: Long = DEFAULT_TICK_MILLIS,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
) : Command<JsonElement, String>(
        id = NAV_TEST_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Routes a walk, replays a GPX that leaves it, and reports the reroute.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val host = session.hostOrNull() ?: return Outcome.Failure(AppError.NotReady)
        val active = engine() ?: return Outcome.Failure(AppError.NotReady)
        val selection = parse(params) ?: return Outcome.Failure(AppError.InvalidInput)
        val request = RouteRequest(waypoints = selection.waypoints, profile = selection.profile)
        val route =
            when (val outcome = active.route(request, CancellationHandle.None)) {
                is Outcome.Failure -> {
                    log("follow route failed: ${outcome.error}")
                    return outcome
                }

                is Outcome.Success -> {
                    outcome.value.firstOrNull() ?: return Outcome.Failure(AppError.NotFound)
                }
            }
        draw(host, route)
        val replay =
            replayGpx(
                route = route,
                detourMeters = selection.detourMeters,
                speedMetersPerSecond = config.speedMetersPerSecond(selection.profile),
            ) ?: return Outcome.Failure(AppError.InvalidInput)
        val source =
            when (
                val outcome =
                    MockGpxLocationSource.fromGpx(
                        replay,
                        selection.tickMillis,
                        delayMillis = delayMillis,
                    )
            ) {
                is Outcome.Success -> {
                    outcome.value
                }

                is Outcome.Failure -> {
                    return outcome
                }
            }
        log(
            "follow-start area=${selection.area} profile=${selection.profile} points=${route.geometry.size} " +
                "base_m=${route.distanceMeters} tick_ms=${selection.tickMillis} detour_m=${selection.detourMeters} " +
                "gate_m=${config.travelMeters(selection.profile)}",
        )
        val navigation =
            NavigationSession(
                follower = followerFactory(),
                routing = active,
                profile = selection.profile,
                config = config.copy(reroute = selection.reroute),
                clockMillis = { System.nanoTime() / 1_000_000L },
                log = log,
            )
        val run =
            when (val outcome = navigation.run(route, source.fixes.filterNotNull())) {
                is Outcome.Failure -> {
                    log("follow failed: ${outcome.error}")
                    return outcome
                }

                is Outcome.Success -> {
                    outcome.value
                }
            }
        val finalRoute = run.finalRoute ?: route
        draw(host, finalRoute)
        val summary =
            "engine=valhalla area=${selection.area} profile=${selection.profile} fixes=${run.fixCount} " +
                "inaccurate_fixes=${run.inaccurateFixCount} off_route_fixes=${run.offRouteFixCount} " +
                "reroutes=${run.rerouteCount} " +
                "arrived=${run.arrived} detection_ms=${run.detectionMillis ?: -1} " +
                "reroute_ms=${run.lastRerouteMillis ?: -1} " +
                "base_m=${route.distanceMeters} final_m=${finalRoute.distanceMeters} " +
                "points=${finalRoute.geometry.size}"
        log("follow-done $summary")
        return Outcome.Success(CommandResult(summary))
    }

    private suspend fun draw(
        host: MapHost,
        route: Route,
    ) {
        host.setRoute(route).onFailure { error -> log("nav route not drawn: $error") }
        val bounds = boundsOf(route.geometry)
        if (bounds != null) {
            host.fitBounds(bounds).onFailure { error -> log("nav route not fitted: $error") }
        }
    }

    private fun parse(params: JsonElement): Selection? {
        val objectValue =
            when (params) {
                is JsonNull -> JsonObject(emptyMap())
                is JsonObject -> params
                else -> return null
            }
        val area =
            (objectValue["area"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: AREA_FIXTURE
        val waypoints =
            when (area) {
                AREA_FIXTURE -> FIXTURE_WAYPOINTS
                AREA_LVIV -> LVIV_WAYPOINTS
                else -> return null
            }
        val tick =
            (objectValue["tick_ms"] as? JsonPrimitive)?.intOrNull?.takeIf { it in MIN_TICK_MILLIS..MAX_TICK_MILLIS }
                ?: tickMillis.toInt()
        val detour =
            (objectValue["detour_m"] as? JsonPrimitive)
                ?.doubleOrNull
                ?.takeIf { it in MIN_DETOUR_METERS..MAX_DETOUR_METERS }
                ?: DEFAULT_DETOUR_METERS
        val reroute = (objectValue["reroute"] as? JsonPrimitive)?.booleanOrNull ?: true
        val profile =
            when ((objectValue["profile"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: PROFILE_WALK) {
                PROFILE_WALK -> RouteProfile.Walk
                PROFILE_BIKE -> RouteProfile.Bike
                PROFILE_HIKE -> RouteProfile.Hike
                else -> return null
            }
        return Selection(area, waypoints, tick.toLong(), detour, reroute, profile)
    }

    /**
     * Builds the replay: a third of the line on route, then the next few
     * vertices pushed sideways, then the rest on route. The sideways push is
     * tapered so the replay moves off the line instead of jumping.
     *
     * Fix timestamps advance with distance at [speedMetersPerSecond], not with
     * the wall tick. The reroute gate is distance-based, but its plausibility
     * bound reads the timestamps, and a timestamp that claims 30 m in half a
     * second would look like a jump rather than travel.
     */
    private fun replayGpx(
        route: Route,
        detourMeters: Double,
        speedMetersPerSecond: Double,
    ): String? {
        val geometry = route.geometry
        if (geometry.size < MIN_GEOMETRY_POINTS) {
            return null
        }
        val oneThird = (geometry.size / 3).coerceAtLeast(1)
        val twoThirds = (geometry.size * 2 / 3).coerceAtLeast(oneThird + 1)
        val onRoute = geometry.take(oneThird)
        val detourSource = geometry.subList(oneThird, twoThirds)
        val backOnRoute = geometry.drop(twoThirds)
        val detour =
            detourSource.mapIndexed { index, point ->
                val bearing = bearingAt(geometry, oneThird + index)
                val taper = ((index + 1) / DETOUR_TAPER_FIXES).coerceIn(0.0, 1.0)
                offset(point, bearing - SIDEWAYS_DEGREES, detourMeters * taper)
            }
        val points = onRoute + detour + backOnRoute
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<gpx version="1.1" creator="grass-maps" xmlns="http://www.topografix.com/GPX/1/1">""")
            appendLine("<trk><name>nav-off-route</name><trkseg>")
            var travelled = 0.0
            points.forEachIndexed { index, point ->
                if (index > 0) {
                    travelled += distanceMeters(points[index - 1], point)
                }
                val offsetMillis = (travelled / speedMetersPerSecond * MILLIS_PER_SECOND).toLong()
                val time = Instant.ofEpochMilli(GPX_BASE_EPOCH_MILLIS + offsetMillis).toString()
                appendLine("""<trkpt lat="${point.latitude}" lon="${point.longitude}"><time>$time</time></trkpt>""")
            }
            appendLine("</trkseg></trk></gpx>")
        }
    }

    /** The great-circle distance used to time the replay, in metres. */
    private fun distanceMeters(
        from: GeoPoint,
        to: GeoPoint,
    ): Double {
        val fromLat = Math.toRadians(from.latitude)
        val toLat = Math.toRadians(to.latitude)
        val deltaLat = toLat - fromLat
        val deltaLon = Math.toRadians(to.longitude - from.longitude)
        val a =
            kotlin.math.sin(deltaLat / 2) * kotlin.math.sin(deltaLat / 2) +
                cos(fromLat) * cos(toLat) * kotlin.math.sin(deltaLon / 2) * kotlin.math.sin(deltaLon / 2)
        return 2 * EARTH_RADIUS_METERS * kotlin.math.asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
    }

    private fun bearingAt(
        geometry: List<GeoPoint>,
        index: Int,
    ): Double {
        val from = geometry[index.coerceIn(0, geometry.lastIndex)]
        val to = geometry[(index + 1).coerceIn(0, geometry.lastIndex)]
        return bearing(from, to)
    }

    private fun bearing(
        from: GeoPoint,
        to: GeoPoint,
    ): Double {
        val fromLat = Math.toRadians(from.latitude)
        val toLat = Math.toRadians(to.latitude)
        val deltaLon = Math.toRadians(to.longitude - from.longitude)
        val y = kotlin.math.sin(deltaLon) * cos(toLat)
        val x = cos(fromLat) * kotlin.math.sin(toLat) - kotlin.math.sin(fromLat) * cos(toLat) * cos(deltaLon)
        return (Math.toDegrees(kotlin.math.atan2(y, x)) + FULL_CIRCLE_DEGREES) % FULL_CIRCLE_DEGREES
    }

    private fun offset(
        origin: GeoPoint,
        bearingDegrees: Double,
        distanceMeters: Double,
    ): GeoPoint {
        val angular = distanceMeters / EARTH_RADIUS_METERS
        val bearing = Math.toRadians(bearingDegrees)
        val latitude = Math.toRadians(origin.latitude)
        val longitude = Math.toRadians(origin.longitude)
        val targetLat =
            kotlin.math.asin(
                kotlin.math.sin(latitude) * cos(angular) +
                    cos(latitude) * kotlin.math.sin(angular) * cos(bearing),
            )
        val targetLon =
            longitude +
                kotlin.math.atan2(
                    kotlin.math.sin(bearing) * kotlin.math.sin(angular) * cos(latitude),
                    cos(angular) - kotlin.math.sin(latitude) * kotlin.math.sin(targetLat),
                )
        return GeoPoint(
            latitude = Math.toDegrees(targetLat),
            longitude = normalizeLongitude(Math.toDegrees(targetLon)),
        )
    }

    private fun normalizeLongitude(degrees: Double): Double =
        ((degrees + HALF_CIRCLE_DEGREES * 3) % FULL_CIRCLE_DEGREES) - HALF_CIRCLE_DEGREES

    private fun boundsOf(points: List<GeoPoint>): BoundingBox? {
        if (points.isEmpty()) {
            return null
        }
        return BoundingBox(
            southWest = GeoPoint(points.minOf { it.latitude }, points.minOf { it.longitude }),
            northEast = GeoPoint(points.maxOf { it.latitude }, points.maxOf { it.longitude }),
        )
    }

    private data class Selection(
        val area: String,
        val waypoints: List<GeoPoint>,
        val tickMillis: Long,
        val detourMeters: Double,
        val reroute: Boolean,
        val profile: RouteProfile,
    )

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
        const val MILLIS_PER_SECOND = 1_000.0
        const val MIN_GEOMETRY_POINTS = 3
        const val PROFILE_WALK = "walk"
        const val PROFILE_BIKE = "bike"
        const val PROFILE_HIKE = "hike"
        const val SIDEWAYS_DEGREES = 90.0
        const val FULL_CIRCLE_DEGREES = 360.0
        const val HALF_CIRCLE_DEGREES = 180.0
        const val MIN_TICK_MILLIS = 50
        const val MAX_TICK_MILLIS = 5_000
        const val MIN_DETOUR_METERS = 40.0
        const val MAX_DETOUR_METERS = 500.0

        /** A short walk past both synthetic modifier lines in the fixture area. */
        val FIXTURE_WAYPOINTS =
            listOf(
                GeoPoint(49.93968, 23.94142),
                GeoPoint(49.9455, 23.94985),
            )

        /** Rynok Square south towards the opera house. */
        val LVIV_WAYPOINTS =
            listOf(
                GeoPoint(49.8419, 24.0316),
                GeoPoint(49.8365, 24.0250),
            )
    }
}

internal fun navCommands(
    session: MapSessionHolder,
    engine: suspend () -> RoutingEngine?,
): List<Command<*, *>> = listOf(TestFollowCommand(session, engine))
