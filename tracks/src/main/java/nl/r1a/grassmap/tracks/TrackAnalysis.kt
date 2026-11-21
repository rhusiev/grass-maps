package nl.r1a.grassmaps.tracks

/**
 * The statistics of one track: its elevation profile, distance and, when the
 * file carries timestamps, its duration, moving time and speeds.
 *
 * [distanceMeters] is the ground distance over [TrackGeometry]'s cleaned line:
 * the haversine sum between the fixes that survive the implausible-speed gate and
 * the simplification at the measured fix noise. A raw sum over the fixes counts
 * their wander as distance; a 2.5-5 m wander turns a 4 km walk into 11 km.
 * [ascentMeters] and [descentMeters] are the sums between the turning points of
 * the elevation line, after the noise filter in [ElevationFilter] that matches
 * OsmAnd's own track analysis, and after [ElevationSmoothing] when the heights
 * carry more noise than that filter assumes. A segment boundary is not a sample:
 * no distance, no elevation difference and no filter run is taken across it. A
 * point without elevation breaks the elevation difference without breaking the
 * distance.
 * [minElevationMeters] and [maxElevationMeters] are null when the track carries
 * no elevation at all, and they read the file's heights, not the filtered line.
 *
 * Timing is measured only between consecutive points of one segment that both
 * carry a timestamp. A segment boundary is a pause, so no time is counted
 * across it. A gap longer than
 * [TrackStatisticsPolicy.pauseGapMillis] is a pause as well, a gap longer than
 * [TrackStatisticsPolicy.fixLossGapFactor] sampling intervals is a loss of fixes
 * whose time is unobserved, and a step in a run of the cleaned line that did not
 * move is standing still: none of them adds to [movingTimeMillis].
 * [durationMillis] is the wall-clock span from the first timestamped point to the
 * last, pauses included.
 *
 * [durationMillis] and [movingTimeMillis] are null when fewer than two points
 * carry a timestamp, and when the last timestamp is earlier than the first. A
 * one-point track gives no time span. When the span is known but no step counts
 * as moving, [movingTimeMillis] is 0.
 * [averageSpeedMetersPerSecond] is [distanceMeters] divided by
 * [movingTimeMillis], so it is null when no time counts as moving. Every step of
 * the cleaned line adds to distance, including one classed as standing still.
 * [maxSpeedMetersPerSecond] is the fastest step between two timestamped points
 * of one segment, over the fixes the gate kept; it is null when there is no such
 * step, and a step slower than the moving threshold still sets it.
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
