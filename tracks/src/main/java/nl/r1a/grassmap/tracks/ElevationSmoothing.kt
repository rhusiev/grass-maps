package nl.r1a.grassmap.tracks

/**
 * The height average [TrackAnalyzer] applies before [ElevationFilter] runs.
 *
 * [ElevationFilter] is OsmAnd's extremum model, and it assumes a height source
 * whose noise is smaller than its 7 m threshold. A barometric sensor or a raw
 * DEM hands it 1-2.5 m of noise instead, and the model then reports the ripple
 * as climbing: on the recorded stress tracks a monotone 400 m climb came back
 * with 40.8 m of invented descent and a five-metre climb with 8.7 m.
 *
 * Averaging the profile over [TrackFilterPolicy.elevationSmoothingMillis] first
 * is what removes that. The average is a plain centred boxcar, which is the
 * least-squares fit of a constant and therefore the best a white noise source
 * allows; it is transparent to a ramp, so a real climb keeps its rise, and it
 * shrinks at the ends rather than pinning the raw first and last heights, so the
 * endpoints' noise does not become a step.
 *
 * The average stays off in three cases, and each keeps a recorded figure exact
 * rather than merely close:
 *
 * - the height noise measured by [TrackNoise.seriesNoise] is at or below
 *   [TrackFilterPolicy.elevationNoiseFloorMeters]. Below that the source is
 *   already cleaner than the model's assumption, so smoothing costs real detail
 *   and buys nothing. The clean OsmAnd oracle measures 0.03-0.11 m here and is
 *   passed through untouched.
 * - the run carries fewer than [TrackFilterPolicy.minimumSmoothedSamples]
 *   heights, or the window would cover more than half of it. A short profile is
 *   made of real chunky steps, and every estimator mistakes those for noise. The
 *   twenty hand-authored fixtures carry at most sixteen heights each, and the
 *   60-point mock walk carries an authored 1.6 m sawtooth that a two-minute
 *   average would flatten to nothing.
 *
 * A run of fewer than four steps is summed raw by [ElevationFilter] either way;
 * smoothing it would only move a figure that has no ripple to remove.
 */
internal object ElevationSmoothing {
    fun gain(
        steps: List<ElevationStep>,
        filters: TrackFilterPolicy,
        intervalMillis: Long?,
    ): ElevationGain {
        val halfWindow =
            TrackNoise.halfWindowSamples(filters.elevationSmoothingMillis, intervalMillis ?: 0L)
        if (halfWindow < 1 || steps.size < filters.minimumSmoothedSamples) {
            return ElevationFilter.sum(steps)
        }
        if (2 * halfWindow + 1 > steps.size / 2) {
            // The window would cover more than half the run: it would flatten the
            // run rather than its noise, and a short profile's own steps are what
            // it has to say. The mock-walk oracle is 60 one-second samples with an
            // authored 1.6 m sawtooth.
            return ElevationFilter.sum(steps)
        }
        val heights = DoubleArray(steps.size) { steps[it].elevationMeters }
        if (TrackNoise.seriesNoise(heights) <= filters.elevationNoiseFloorMeters) {
            return ElevationFilter.sum(steps)
        }
        val smoothed = TrackNoise.smoothed(heights, halfWindow)
        val average =
            List(steps.size) { index ->
                ElevationStep(
                    distanceMeters = steps[index].distanceMeters,
                    elevationMeters = smoothed[index],
                )
            }
        return ElevationFilter.sum(average)
    }
}
