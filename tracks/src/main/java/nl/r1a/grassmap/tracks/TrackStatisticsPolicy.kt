package nl.r1a.grassmap.tracks

/**
 * What [TrackAnalyzer] counts as moving time.
 *
 * A pause is either a segment boundary - the exporter already split the track
 * because the activity stopped - or a gap between two timestamped points of one
 * segment longer than [pauseGapMillis]. Time across either one does not count as
 * moving.
 *
 * A step between two timestamped points is moving when its ground speed is at
 * least [movingSpeedMetersPerSecond]. A slower step still adds its distance,
 * and still competes for the maximum speed, but its time is the time spent
 * standing still.
 *
 * The defaults are chosen, not derived from OsmAnd. The 10-track oracle in
 * `test-data/tracks/osmand-oracle/README.md` is where they meet real numbers.
 */
data class TrackStatisticsPolicy(
    val pauseGapMillis: Long = DEFAULT_PAUSE_GAP_MILLIS,
    val movingSpeedMetersPerSecond: Double = DEFAULT_MOVING_SPEED_METERS_PER_SECOND,
) {
    init {
        require(pauseGapMillis > 0) { "pauseGapMillis must be positive" }
        require(movingSpeedMetersPerSecond >= 0.0) { "movingSpeedMetersPerSecond must not be negative" }
        require(movingSpeedMetersPerSecond.isFinite()) { "movingSpeedMetersPerSecond must be finite" }
    }

    companion object {
        /** Two minutes between two fixes is a stop, not a slow walk. */
        const val DEFAULT_PAUSE_GAP_MILLIS: Long = 120_000

        /** Half a metre per second, below a walking pace. */
        const val DEFAULT_MOVING_SPEED_METERS_PER_SECOND: Double = 0.5
    }
}
