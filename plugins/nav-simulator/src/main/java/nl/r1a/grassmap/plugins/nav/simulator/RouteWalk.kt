package nl.r1a.grassmap.plugins.nav.simulator

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The route line as a distance scale. [pointAt] places a fix at a distance
 * along the line rather than at a vertex, so the simulated speed does not
 * depend on how densely the geometry is sampled.
 */
internal class RouteWalk(
    private val geometry: List<GeoPoint>,
) {
    private val cumulative = DoubleArray(geometry.size)

    val totalMeters: Double

    init {
        require(geometry.size >= MIN_ROUTE_POINTS)
        var running = 0.0
        for (index in 1 until geometry.size) {
            running += distanceMeters(geometry[index - 1], geometry[index])
            cumulative[index] = running
        }
        totalMeters = running
    }

    /** The point [distanceMeters] from the start of the line. */
    fun pointAt(distanceMeters: Double): GeoPoint {
        if (distanceMeters <= 0.0) {
            return geometry.first()
        }
        if (distanceMeters >= totalMeters) {
            return geometry.last()
        }
        val index = segmentEndIndex(distanceMeters)
        val start = geometry[index - 1]
        val end = geometry[index]
        val startMeters = cumulative[index - 1]
        return offset(start, bearingDegrees(start, end), distanceMeters - startMeters)
    }

    /** The bearing of the segment that contains [distanceMeters]. */
    fun bearingAt(distanceMeters: Double): Double {
        var index = segmentEndIndex(distanceMeters.coerceIn(0.0, totalMeters))
        while (index > 1 && cumulative[index] == cumulative[index - 1]) {
            index -= 1
        }
        return bearingDegrees(geometry[index - 1], geometry[index])
    }

    /** The first segment whose end is beyond [distanceMeters]. */
    private fun segmentEndIndex(distanceMeters: Double): Int {
        var index = 1
        while (index < geometry.size - 1 && cumulative[index] <= distanceMeters) {
            index += 1
        }
        return index
    }

    private companion object {
        const val MIN_ROUTE_POINTS = 2
        const val EARTH_RADIUS_METERS = 6_371_008.8
        const val FULL_CIRCLE_DEGREES = 360.0
        const val HALF_CIRCLE_DEGREES = 180.0

        fun distanceMeters(
            from: GeoPoint,
            to: GeoPoint,
        ): Double {
            val fromLat = Math.toRadians(from.latitude)
            val toLat = Math.toRadians(to.latitude)
            val deltaLat = toLat - fromLat
            val deltaLon = Math.toRadians(to.longitude - from.longitude)
            val a =
                sin(deltaLat / 2) * sin(deltaLat / 2) +
                    cos(fromLat) * cos(toLat) * sin(deltaLon / 2) * sin(deltaLon / 2)
            return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }

        fun bearingDegrees(
            from: GeoPoint,
            to: GeoPoint,
        ): Double {
            val fromLat = Math.toRadians(from.latitude)
            val toLat = Math.toRadians(to.latitude)
            val deltaLon = Math.toRadians(to.longitude - from.longitude)
            val y = sin(deltaLon) * cos(toLat)
            val x = cos(fromLat) * sin(toLat) - sin(fromLat) * cos(toLat) * cos(deltaLon)
            return (Math.toDegrees(atan2(y, x)) + FULL_CIRCLE_DEGREES) % FULL_CIRCLE_DEGREES
        }

        fun offset(
            origin: GeoPoint,
            bearing: Double,
            distanceMeters: Double,
        ): GeoPoint {
            val angular = distanceMeters / EARTH_RADIUS_METERS
            val radians = Math.toRadians(bearing)
            val latitude = Math.toRadians(origin.latitude)
            val longitude = Math.toRadians(origin.longitude)
            val targetLat =
                asin(
                    sin(latitude) * cos(angular) +
                        cos(latitude) * sin(angular) * cos(radians),
                )
            val targetLon =
                longitude +
                    atan2(
                        sin(radians) * sin(angular) * cos(latitude),
                        cos(angular) - sin(latitude) * sin(targetLat),
                    )
            return GeoPoint(
                latitude = Math.toDegrees(targetLat),
                longitude = normalizeLongitude(Math.toDegrees(targetLon)),
            )
        }

        fun normalizeLongitude(degrees: Double): Double {
            val wrapped = (degrees + HALF_CIRCLE_DEGREES * 3) % FULL_CIRCLE_DEGREES
            return wrapped - HALF_CIRCLE_DEGREES
        }
    }
}
