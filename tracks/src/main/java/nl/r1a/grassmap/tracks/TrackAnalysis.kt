package nl.r1a.grassmap.tracks

/**
 * The statistics of one track: its elevation profile, distance and, when the
 * file carries timestamps, its duration, moving time and speeds.
 *
 * [distanceMeters] is the haversine ground distance over consecutive points of
 * one segment. [ascentMeters] and [descentMeters] are the sum of every positive
 * and negative elevation difference between consecutive points of a segment,
 * with no smoothing and no noise threshold. A segment boundary is not a sample:
 * no distance and no elevation difference is taken across it. A point without
 * elevation breaks the elevation difference without breaking the distance.
 * [minElevationMeters] and [maxElevationMeters] are null when the track carries
 * no elevation at all.
 *
 * Timing is measured only between consecutive points of one segment that both
 * carry a timestamp. A segment boundary is a pause, so no time is counted
 * across it. A gap longer than
 * [TrackStatisticsPolicy.pauseGapMillis] is a pause as well, and a step slower
 * than [TrackStatisticsPolicy.movingSpeedMetersPerSecond] is standing still:
 * neither adds to [movingTimeMillis]. [durationMillis] is the wall-clock span
 * from the first timestamped point to the last, pauses included.
 *
 * [durationMillis] and [movingTimeMillis] are null when fewer than two points
 * carry a timestamp, and when the last timestamp is earlier than the first. A
 * one-point track gives no time span. When the span is known but no step counts
 * as moving, [movingTimeMillis] is 0.
 * [averageSpeedMetersPerSecond] is [distanceMeters] divided by
 * [movingTimeMillis], so it is null when no time counts as moving. Every step
 * adds to distance, including one classed as standing still.
 * [maxSpeedMetersPerSecond] is the fastest step between two timestamped points
 * of one segment; it is null when there is no such step, and a step slower than
 * the moving threshold still sets it.
 */
data class TrackAnalysis(
    val pointCount: Int,
    val distanceMeters: Double,
    val ascentMeters: Double,
    val descentMeters: Double,
    val minElevationMeters: Double?,
    val maxElevationMeters: Double?,
    val durationMillis: Long? = null,
    val movingTimeMillis: Long? = null,
    val averageSpeedMetersPerSecond: Double? = null,
    val maxSpeedMetersPerSecond: Double? = null,
)
