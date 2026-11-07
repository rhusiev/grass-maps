package nl.r1a.grassmap.tracks

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.Track
import nl.r1a.grassmap.plugin.api.model.TrackPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A pure elevation-profile function over a track's points.
 *
 * Distance is the haversine ground distance with the mean Earth radius
 * [EARTH_RADIUS_METERS]. Elevation deltas use the raw `ele` values, so a noisy
 * file produces a large ascent; smoothing and a noise threshold are T-0402's.
 */
class TrackAnalyzer {
    fun analyze(track: Track): TrackAnalysis {
        var pointCount = 0
        var totalDistanceMeters = 0.0
        var ascentMeters = 0.0
        var descentMeters = 0.0
        var minElevation: Double? = null
        var maxElevation: Double? = null

        for (segment in track.segments) {
            var previous: TrackPoint? = null
            for (point in segment.points) {
                pointCount += 1
                point.elevationMeters?.let { elevation ->
                    if (minElevation == null || elevation < minElevation) {
                        minElevation = elevation
                    }
                    if (maxElevation == null || elevation > maxElevation) {
                        maxElevation = elevation
                    }
                }
                previous?.let { before ->
                    totalDistanceMeters += distanceMeters(before.position, point.position)
                    val from = before.elevationMeters
                    val to = point.elevationMeters
                    if (from != null && to != null) {
                        val delta = to - from
                        if (delta > 0.0) {
                            ascentMeters += delta
                        } else {
                            descentMeters += -delta
                        }
                    }
                }
                previous = point
            }
        }

        return TrackAnalysis(
            pointCount = pointCount,
            distanceMeters = totalDistanceMeters,
            ascentMeters = ascentMeters,
            descentMeters = descentMeters,
            minElevationMeters = minElevation,
            maxElevationMeters = maxElevation,
        )
    }

    private fun distanceMeters(
        from: GeoPoint,
        to: GeoPoint,
    ): Double {
        val fromLatitude = Math.toRadians(from.latitude)
        val toLatitude = Math.toRadians(to.latitude)
        val latitudeDelta = toLatitude - fromLatitude
        val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
        val haversine =
            sin(latitudeDelta / 2.0) * sin(latitudeDelta / 2.0) +
                cos(fromLatitude) * cos(toLatitude) *
                sin(longitudeDelta / 2.0) * sin(longitudeDelta / 2.0)
        return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
    }

    private companion object {
        /** IUGG mean Earth radius. The oracle documents the same constant. */
        const val EARTH_RADIUS_METERS: Double = 6_371_008.8
    }
}
