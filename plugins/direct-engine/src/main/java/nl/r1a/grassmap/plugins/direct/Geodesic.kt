package nl.r1a.grassmap.plugins.direct

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Great-circle arithmetic for the direct engine.
 *
 * The route is the geodesic between two points, so the length is the haversine
 * distance of the pair and the line is the same arc, sampled. Sampling with
 * linear longitude/latitude interpolation would bend the geometry away from the
 * geodesic and make the drawn line disagree with the declared length; the
 * points below are on the arc itself, so the sum of their haversine lengths is
 * the endpoint distance up to floating point.
 *
 * The radius matches the rest of the repository (navigation's locator and the
 * Valhalla engine's straight-line guard), so a length computed here and a length
 * measured by a follower are the same number.
 */
internal object Geodesic {
    const val EARTH_RADIUS_METRES: Double = 6_371_000.0

    /**
     * The great-circle distance between [from] and [to], in metres.
     *
     * The haversine form stays accurate for the short legs a route has, where
     * the law-of-cosines form loses precision.
     */
    fun distanceMetres(
        from: GeoPoint,
        to: GeoPoint,
    ): Double {
        val fromLatitude = Math.toRadians(from.latitude)
        val toLatitude = Math.toRadians(to.latitude)
        val latitudeDelta = toLatitude - fromLatitude
        val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
        val sinLatitude = sin(latitudeDelta / 2)
        val sinLongitude = sin(longitudeDelta / 2)
        val a = sinLatitude * sinLatitude + cos(fromLatitude) * cos(toLatitude) * sinLongitude * sinLongitude
        return 2 * EARTH_RADIUS_METRES * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /**
     * [segments] equal arcs of the geodesic from [from] to [to], as
     * `segments + 1` points. The two ends are the exact input points; the
     * interior points are spherical interpolations, so every consecutive pair
     * is itself a geodesic and the sampled line does not cut the corner a
     * Mercator chord would.
     *
     * Two guards keep the interpolation away from its singular cases: a leg whose
     * arc has a sine below [MIN_ARC_SIN] returns its two ends, and an
     * antipodal-ish leg (the sine of the arc near zero) does the same instead
     * of dividing by it. The engine's distance cap keeps a real leg far from
     * the second case.
     */
    fun polyline(
        from: GeoPoint,
        to: GeoPoint,
        segments: Int,
    ): List<GeoPoint> {
        require(segments >= 1) { "a leg has at least one arc" }
        if (segments == 1) {
            return listOf(from, to)
        }
        val start = unitVector(from)
        val end = unitVector(to)
        val arc = arcBetween(start, end)
        val sinArc = sin(arc)
        if (sinArc < MIN_ARC_SIN) {
            return listOf(from, to)
        }
        val interior =
            (1 until segments).map { index ->
                val fraction = index.toDouble() / segments
                val startWeight = sin((1.0 - fraction) * arc) / sinArc
                val endWeight = sin(fraction * arc) / sinArc
                pointOf(
                    x = start.x * startWeight + end.x * endWeight,
                    y = start.y * startWeight + end.y * endWeight,
                    z = start.z * startWeight + end.z * endWeight,
                )
            }
        return buildList(segments + 1) {
            add(from)
            addAll(interior)
            add(to)
        }
    }

    private fun unitVector(point: GeoPoint): UnitVector {
        val latitude = Math.toRadians(point.latitude)
        val longitude = Math.toRadians(point.longitude)
        val radial = cos(latitude)
        return UnitVector(
            x = radial * cos(longitude),
            y = radial * sin(longitude),
            z = sin(latitude),
        )
    }

    private fun arcBetween(
        start: UnitVector,
        end: UnitVector,
    ): Double {
        val dot = start.x * end.x + start.y * end.y + start.z * end.z
        val crossX = start.y * end.z - start.z * end.y
        val crossY = start.z * end.x - start.x * end.z
        val crossZ = start.x * end.y - start.y * end.x
        return atan2(sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ), dot)
    }

    private fun pointOf(
        x: Double,
        y: Double,
        z: Double,
    ): GeoPoint {
        val length = sqrt(x * x + y * y + z * z)
        return GeoPoint(
            latitude = Math.toDegrees(asin((z / length).coerceIn(-1.0, 1.0))),
            longitude = Math.toDegrees(atan2(y, x)),
        )
    }

    /** The sine of a leg this short is below what the interpolation can resolve. */
    private const val MIN_ARC_SIN = 1e-12

    private data class UnitVector(
        val x: Double,
        val y: Double,
        val z: Double,
    )
}
