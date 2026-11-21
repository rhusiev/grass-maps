package nl.r1a.grassmaps.tracks

/**
 * What [TrackAnalyzer] counts as moving time.
 *
 * A pause is either a segment boundary - the exporter already split the track
 * because the activity stopped - or a gap between two timestamped points of one
 * segment longer than [pauseGapMillis]. Time across either one does not count as
 * moving.
 *
 * A fix-loss gap is shorter than a pause but much longer than the track's own
 * sampling interval: a step longer than [fixLossGapFactor] intervals. The
 * receiver lost the signal there, so the walker's time across it was never
 * observed. It is not a pause - the distance across the gap still counts, and
 * [TrackAnalyzer] measures it as the chord between the last fix before the loss
 * and the first after it - but it adds no moving time. The recorded dropout
 * tracks are what this is for: bridging a 75 s loss adds 71 s to a span of
 * 8 000 s, which is 0.9 % against a 0.5 % bound, and the authored truth excludes
 * it.
 *
 * A step between two timestamped points is moving when the straight run of the
 * cleaned line it belongs to moved: that run's length over the time observed in
 * it, at least [movingSpeedMetersPerSecond]. A standstill still adds its
 * distance and still competes for the maximum speed, but its time is the time
 * spent standing still. Measuring the run rather than the step is deliberate: a
 * noisy fix pair makes a walking step read 0.3 m/s, while thirty steps of the
 * same walk average to the walk.
 *
 * The defaults are chosen, not derived from OsmAnd. The 10-track oracle in
 * `test-data/tracks/osmand-oracle/README.md` is where they meet real numbers.
 */
data class TrackStatisticsPolicy(
    val pauseGapMillis: Long = DEFAULT_PAUSE_GAP_MILLIS,
    val movingSpeedMetersPerSecond: Double = DEFAULT_MOVING_SPEED_METERS_PER_SECOND,
    val fixLossGapFactor: Double = DEFAULT_FIX_LOSS_GAP_FACTOR,
) {
    init {
        require(pauseGapMillis > 0) { "pauseGapMillis must be positive" }
        require(movingSpeedMetersPerSecond >= 0.0) { "movingSpeedMetersPerSecond must not be negative" }
        require(movingSpeedMetersPerSecond.isFinite()) { "movingSpeedMetersPerSecond must be finite" }
        require(fixLossGapFactor >= 1.0) { "fixLossGapFactor must be at least one interval" }
        require(fixLossGapFactor.isFinite()) { "fixLossGapFactor must be finite" }
    }

    companion object {
        /** Two minutes between two fixes is a stop, not a slow walk. */
        const val DEFAULT_PAUSE_GAP_MILLIS: Long = 120_000

        /** Half a metre per second, below a walking pace. */
        const val DEFAULT_MOVING_SPEED_METERS_PER_SECOND: Double = 0.5

        /**
         * Four sampling intervals. A receiver that sampled every 4 s and then
         * missed 20 fixes has lost 79 s of observation, while a smart-recording
         * source that stores every fifth fix is still sampling. Four intervals is
         * the smallest factor that keeps the second case a sample and the first a
         * gap on the recorded tracks, whose losses are 16-20 intervals long.
         */
        const val DEFAULT_FIX_LOSS_GAP_FACTOR: Double = 4.0
    }
}
