package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Route
import java.time.Instant
import kotlin.math.cos

/**
 * The Lviv route the reroute benchmarks plan: Rynok Square south towards the
 * opera house. The debug command and the measurement screen plan this same
 * line, so their numbers describe the same request.
 */
internal val LVIV_BENCH_WAYPOINTS =
    listOf(
        GeoPoint(49.8419, 24.0316),
        GeoPoint(49.8365, 24.0250),
    )

/**
 * The GPX replay the navigation measurements follow: a third of the line on
 * route, then the next few vertices pushed sideways, then the rest on route.
 *
 * One builder serves the debug command `core.nav.test_follow` and the
 * measurement screen, so the reroute definition cannot drift between the
 * adb-driven benchmark and the on-device run. The sideways push is tapered so
 * the replay moves off the line instead of jumping.
 *
 * Fix timestamps advance with distance at [speedMetersPerSecond], not with the
 * wall tick. The reroute gate is distance-based, but its plausibility bound
 * reads the timestamps, and a timestamp that claims 30 m in half a second
 * would look like a jump rather than travel.
 */
internal object NavReplay {
    const val DEFAULT_DETOUR_METERS = 45.0
    const val MIN_DETOUR_METERS = 40.0
    const val MAX_DETOUR_METERS = 500.0

    private const val MIN_GEOMETRY_POINTS = 3
    private const val SIDEWAYS_DEGREES = 90.0
    private const val DETOUR_TAPER_FIXES = 3.0
    private const val FULL_CIRCLE_DEGREES = 360.0
    private const val HALF_CIRCLE_DEGREES = 180.0
    private const val MILLIS_PER_SECOND = 1_000.0
    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val GPX_BASE_EPOCH_MILLIS = 1_767_225_600_000L

    /** The replay for [route], or null when the line is too short to split. */
    fun gpx(
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
}
