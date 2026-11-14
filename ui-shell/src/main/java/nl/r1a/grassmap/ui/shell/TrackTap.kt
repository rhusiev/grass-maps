package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.MapTrack
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Which track a tap on the map selected (T-0415).
 *
 * The engine turns the tap into a map point, so this test never repeats the
 * map's projection: it measures the tap against each drawn segment in local
 * metres and picks the nearest one within [TAP_TOLERANCE_DP] of finger reach.
 * [metresPerDp] comes from the camera and the screen, so the same finger reach
 * selects a track at any zoom.
 *
 * Hidden tracks never reach this function: the store leaves them out of the
 * drawable list, so a hidden track has no geometry to hit. A track with no
 * drawable segment is skipped for the same reason the map skips it.
 */
object TrackTap {
    /** How far a tap may sit from a drawn line and still select it. */
    const val TAP_TOLERANCE_DP: Float = 14f

    /**
     * The ID of the nearest track within [toleranceMeters], or null.
     *
     * [metresPerDp] is the map's own scale at the tap: metres on the ground
     * that one density-independent pixel covers. The caller computes it from
     * the camera, so this object needs no camera and no second Earth model.
     */
    fun hit(
        tracks: List<MapTrack>,
        tap: GeoPoint,
        metresPerDp: Double,
    ): String? {
        if (!metresPerDp.isFinite() || metresPerDp <= 0.0) {
            return null
        }
        val tolerance = TAP_TOLERANCE_DP * metresPerDp
        val metresPerDegreeLatitude = EARTH_METRES_PER_DEGREE
        val metresPerDegreeLongitude = EARTH_METRES_PER_DEGREE * cos(Math.toRadians(tap.latitude))
        var bestId: String? = null
        var bestDistance = tolerance
        for (track in tracks) {
            for (segment in track.segments) {
                var previousX = 0.0
                var previousY = 0.0
                var hasPrevious = false
                for (point in segment) {
                    val x = (point.longitude - tap.longitude) * metresPerDegreeLongitude
                    val y = (point.latitude - tap.latitude) * metresPerDegreeLatitude
                    if (hasPrevious) {
                        val distance = distanceToSegment(x, y, previousX, previousY)
                        if (distance <= bestDistance) {
                            bestDistance = distance
                            bestId = track.id
                        }
                    }
                    previousX = x
                    previousY = y
                    hasPrevious = true
                }
            }
        }
        return bestId
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
