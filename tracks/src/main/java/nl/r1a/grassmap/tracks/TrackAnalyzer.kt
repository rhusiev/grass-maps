package nl.r1a.grassmap.tracks

import nl.r1a.grassmap.plugin.api.model.Track
import nl.r1a.grassmap.plugin.api.model.TrackPoint

/**
 * A pure statistics function over a track's points, with no I/O and no state.
 *
 * Distance is the haversine ground distance with the mean Earth radius
 * [EARTH_RADIUS_METERS]. Elevation deltas use the raw `ele` values, so a noisy
 * file produces a large ascent. Timing follows [TrackStatisticsPolicy]: a
 * segment boundary, a pause gap and a step slower than the moving speed do not
 * count as moving time.
 */
class TrackAnalyzer(
    private val policy: TrackStatisticsPolicy = TrackStatisticsPolicy(),
) {
    fun analyze(track: Track): TrackAnalysis {
        var pointCount = 0
        var totalDistanceMeters = 0.0
        var ascentMeters = 0.0
        var descentMeters = 0.0
        var minElevation: Double? = null
        var maxElevation: Double? = null
        var timestampCount = 0
        var firstTimestampMillis: Long? = null
        var lastTimestampMillis: Long? = null
        var movingMillis = 0L
        var maxSpeed: Double? = null

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
                point.timeMillis?.let { time ->
                    timestampCount += 1
                    if (firstTimestampMillis == null) {
                        firstTimestampMillis = time
                    }
                    lastTimestampMillis = time
                }
                previous?.let { before ->
                    val stepMeters = distanceMeters(before.position, point.position)
                    totalDistanceMeters += stepMeters
                    val fromElevation = before.elevationMeters
                    val toElevation = point.elevationMeters
                    if (fromElevation != null && toElevation != null) {
                        val delta = toElevation - fromElevation
                        if (delta > 0.0) {
                            ascentMeters += delta
                        } else {
                            descentMeters += -delta
                        }
                    }
                    val step = timedStep(before, point)
                    if (step != null) {
                        val speed = stepMeters / (step / MILLIS_PER_SECOND)
                        if (maxSpeed == null || speed > maxSpeed) {
                            maxSpeed = speed
                        }
                        if (speed >= policy.movingSpeedMetersPerSecond) {
                            movingMillis += step
                        }
                    }
                }
                previous = point
            }
        }

        val durationMillis =
            firstTimestampMillis?.let { first ->
                lastTimestampMillis?.takeIf { last -> last >= first && timestampCount >= 2 }?.minus(first)
            }
        val movingTime = durationMillis?.let { movingMillis }
        val averageSpeed =
            movingTime
                ?.takeIf { it > 0 }
                ?.let { totalDistanceMeters / (it / MILLIS_PER_SECOND) }

        return TrackAnalysis(
            pointCount = pointCount,
            distanceMeters = totalDistanceMeters,
            ascentMeters = ascentMeters,
            descentMeters = descentMeters,
            minElevationMeters = minElevation,
            maxElevationMeters = maxElevation,
            durationMillis = durationMillis,
            movingTimeMillis = movingTime,
            averageSpeedMetersPerSecond = averageSpeed,
            maxSpeedMetersPerSecond = maxSpeed,
        )
    }

    /**
     * The time of one step when both ends carry a timestamp, it moves forward
     * and it is not a pause. Null means the step carries no moving time.
     */
    private fun timedStep(
        from: TrackPoint,
        to: TrackPoint,
    ): Long? {
        val fromTime = from.timeMillis ?: return null
        val toTime = to.timeMillis ?: return null
        val delta = toTime - fromTime
        return delta.takeIf { it in 1..policy.pauseGapMillis }
    }

    private companion object {
        const val MILLIS_PER_SECOND: Double = 1_000.0
    }
}
