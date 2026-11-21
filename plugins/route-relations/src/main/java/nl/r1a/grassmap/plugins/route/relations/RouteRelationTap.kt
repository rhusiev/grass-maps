package nl.r1a.grassmaps.plugins.route.relations

import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import kotlin.math.cos
import kotlin.math.hypot

/**
 * The map tap's route-relation hit test (T-0420).
 *
 * The geometry is [RouteRelationsGeometry]'s assembled relation, so a tap
 * selects a relation whose member way lies under the finger. A tap between two
 * relations selects the nearer line, and a line outside the finger's reach
 * selects nothing. A gap between members is not a line: a tap in the gap
 * selects nothing, because the drawing has no geometry there either.
 *
 * The caller gives the map's own scale, as the track tap does. This module has
 * no camera and no second Earth model.
 */
object RouteRelationTap {
    /** How far a tap may sit from a drawn member way and still select it. */
    const val TAP_TOLERANCE_DP: Float = 14f

    fun hit(
        routes: List<AssembledRoute>,
        tap: GeoPoint,
        metresPerDp: Double,
    ): AssembledRoute? {
        if (!metresPerDp.isFinite() || metresPerDp <= 0.0) {
            return null
        }
        val tolerance = TAP_TOLERANCE_DP * metresPerDp
        val metresPerDegreeLatitude = EARTH_METRES_PER_DEGREE
        val metresPerDegreeLongitude = EARTH_METRES_PER_DEGREE * cos(Math.toRadians(tap.latitude))
        var best: AssembledRoute? = null
        var bestDistance = tolerance
        for (route in routes) {
            for (segment in route.segments) {
                var previousX = 0.0
                var previousY = 0.0
                var hasPrevious = false
                for (point in segment.points) {
                    val x = (point.longitude - tap.longitude) * metresPerDegreeLongitude
                    val y = (point.latitude - tap.latitude) * metresPerDegreeLatitude
                    if (hasPrevious) {
                        val distance = distanceToSegment(x, y, previousX, previousY)
                        if (distance <= bestDistance) {
                            bestDistance = distance
                            best = route
                        }
                    }
                    previousX = x
                    previousY = y
                    hasPrevious = true
                }
            }
        }
        return best
    }

    private fun distanceToSegment(
        endX: Double,
        endY: Double,
        startX: Double,
        startY: Double,
    ): Double {
        val dx = endX - startX
        val dy = endY - startY
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 0.0) {
            return hypot(endX - startX, endY - startY)
        }
        val raw = -((startX) * dx + (startY) * dy) / lengthSquared
        val t = raw.coerceIn(0.0, 1.0)
        return hypot(startX + t * dx, startY + t * dy)
    }

    /** One degree of latitude, close enough for a finger's reach at any zoom. */
    private const val EARTH_METRES_PER_DEGREE = 111_320.0
}
