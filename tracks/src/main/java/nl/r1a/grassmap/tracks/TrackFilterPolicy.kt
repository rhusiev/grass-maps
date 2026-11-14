package nl.r1a.grassmap.tracks

/**
 * The strengths that [TrackAnalyzer] applies when the fixes or the heights are
 * noisier than the source it was built for.
 *
 * Every figure here answers one question, and every one of them is scaled by a
 * *measured* quantity rather than by the point count, so the same defaults serve
 * a 1 s phone log and a 30 s watch export:
 *
 * - [plausibleSpeedFactor] and [plausibleSpeedFloorMetersPerSecond] decide when
 *   a fix is a bad measurement rather than a journey. A step is implausible when
 *   its ground speed exceeds the larger of the two: the track's own median speed
 *   times the factor, or the floor. The floor keeps a standstill (median speed
 *   near zero) from making every jitter step implausible; the factor keeps a
 *   fast track (a ride, a train) from losing a real fix to a ceiling chosen for
 *   walking. A fix is dropped only when the steps into and out of it are both
 *   implausible, because a single fast step is a measurement, while a fast step
 *   in and out of the same fix is a spike.
 * - [fixNoiseTolerance] is the simplification tolerance of the cleaned line, in
 *   multiples of [TrackNoise.positionNoiseMeters]. The cleaned line is what the
 *   distance is measured on; a white wander of the fixes inflates a raw sum by
 *   30-180 % on the fixtures this was built against, and the tolerance is what
 *   removes it without cutting a real corner (a corner is farther than the
 *   tolerance from the chord, so the line keeps it).
 * - [elevationSmoothingMillis] is the length of the height average applied
 *   before [ElevationFilter] runs, and [elevationNoiseFloorMeters] is the height
 *   noise below which it stays off. [ElevationFilter] is OsmAnd's extremum model
 *   and assumes a height source whose noise is smaller than its 7 m threshold;
 *   above that assumption the model reports the ripple as climbing, so the
 *   profile is averaged over the window first. [minimumSmoothedSamples] keeps the
 *   average off a profile too short for it: a hand-authored six-point file has
 *   chunky steps that look like noise to any estimator, and the twenty recorded
 *   fixtures are exactly that.
 *
 * The defaults are chosen against the two oracles in `test-data/tracks/`, not
 * derived: `oracle/` (the clean OsmAnd agreement) and `oracle-realism/` (six
 * tracks with emulated noise and an authored truth). `oracle-realism/README.md`
 * records what each default buys and what it costs.
 */
data class TrackFilterPolicy(
    val plausibleSpeedFactor: Double = DEFAULT_PLAUSIBLE_SPEED_FACTOR,
    val plausibleSpeedFloorMetersPerSecond: Double = DEFAULT_PLAUSIBLE_SPEED_FLOOR_METERS_PER_SECOND,
    val fixNoiseTolerance: Double = DEFAULT_FIX_NOISE_TOLERANCE,
    val elevationSmoothingMillis: Long = DEFAULT_ELEVATION_SMOOTHING_MILLIS,
    val elevationNoiseFloorMeters: Double = DEFAULT_ELEVATION_NOISE_FLOOR_METERS,
    val minimumSmoothedSamples: Int = DEFAULT_MINIMUM_SMOOTHED_SAMPLES,
) {
    init {
        require(plausibleSpeedFactor > 1.0) { "plausibleSpeedFactor must exceed one" }
        require(plausibleSpeedFloorMetersPerSecond >= 0.0) {
            "plausibleSpeedFloorMetersPerSecond must not be negative"
        }
        require(plausibleSpeedFloorMetersPerSecond.isFinite()) {
            "plausibleSpeedFloorMetersPerSecond must be finite"
        }
        require(fixNoiseTolerance >= 0.0) { "fixNoiseTolerance must not be negative" }
        require(fixNoiseTolerance.isFinite()) { "fixNoiseTolerance must be finite" }
        require(elevationSmoothingMillis > 0) { "elevationSmoothingMillis must be positive" }
        require(elevationNoiseFloorMeters >= 0.0) { "elevationNoiseFloorMeters must not be negative" }
        require(minimumSmoothedSamples >= MINIMUM_WINDOW_SAMPLES) {
            "minimumSmoothedSamples must leave a window to average"
        }
    }

    companion object {
        /** Six times the track's own median speed still leaves a walk's jitter alone. */
        const val DEFAULT_PLAUSIBLE_SPEED_FACTOR: Double = 6.0

        /** Five metres per second is a run, not a walk; a standstill cannot raise its own limit. */
        const val DEFAULT_PLAUSIBLE_SPEED_FLOOR_METERS_PER_SECOND: Double = 5.0

        /**
         * Eight times the fix-noise residual scale. On the recorded stress tracks
         * this removes 70-96 % of the raw jitter inflation while a real corner
         * survives, and a straight line is unchanged.
         */
        const val DEFAULT_FIX_NOISE_TOLERANCE: Double = 8.0

        /**
         * Two minutes. Terrain and a barometric wander both move on the scale of
         * minutes, while the sensor noise moves on the scale of samples, so an
         * average this long separates them. Measured on the six stress tracks:
         * shorter windows leave the ascent ripple, longer ones start to average
         * away a real five-metre climb.
         */
        const val DEFAULT_ELEVATION_SMOOTHING_MILLIS: Long = 120_000

        /**
         * Four tenths of a metre. Below this the height source is already cleaner
         * than [ElevationFilter]'s 7 m extremum threshold, so smoothing only costs
         * real detail; the clean OsmAnd tracks measure 0.03-0.11 m here.
         */
        const val DEFAULT_ELEVATION_NOISE_FLOOR_METERS: Double = 0.4

        /**
         * Thirty-two samples. The twenty recorded hand-authored fixtures carry at
         * most sixteen heights, and a profile that short is made of real chunky
         * steps rather than noise; the smallest clean oracle track carries 47.
         */
        const val DEFAULT_MINIMUM_SMOOTHED_SAMPLES: Int = 32

        /** A five-point quadratic fit needs five samples, so no smaller floor is useful. */
        private const val MINIMUM_WINDOW_SAMPLES: Int = 5
    }
}
