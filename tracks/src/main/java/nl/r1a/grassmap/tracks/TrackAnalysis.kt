package nl.r1a.grassmap.tracks

/**
 * The elevation profile and distance of one track.
 *
 * [ascentMeters] and [descentMeters] are the sum of every positive and negative
 * elevation difference between consecutive points of a segment, with no
 * smoothing and no noise threshold. A segment boundary is not a sample: no
 * difference is taken across it. [minElevationMeters] and [maxElevationMeters]
 * are null when the track carries no elevation at all.
 */
data class TrackAnalysis(
    val pointCount: Int,
    val distanceMeters: Double,
    val ascentMeters: Double,
    val descentMeters: Double,
    val minElevationMeters: Double?,
    val maxElevationMeters: Double?,
)
