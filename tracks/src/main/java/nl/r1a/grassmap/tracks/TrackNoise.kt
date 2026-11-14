package nl.r1a.grassmap.tracks

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The noise measurements the filters are scaled by.
 *
 * A raw second difference can not tell a sensor from a curve: a smooth bend and
 * a white wander both make one. The residual of a centred five-point quadratic
 * fit can. A straight line, a constant slope and a constant curvature all
 * satisfy that fit, so the residual is the part of the sample no smooth motion
 * explains - the noise. The scale is the median absolute residual over 0.6745,
 * the factor a median absolute deviation uses, so a Gaussian residual of 1 m
 * reads about 1 m and one outlier can not move it.
 *
 * The residual is about 0.72 of the per-axis noise of a Gaussian series: the fit
 * absorbs part of the sample it is centred on. Filter strengths are expressed in
 * multiples of this residual scale rather than in multiples of sigma, so the
 * figure a policy carries is the figure measured here.
 */
internal object TrackNoise {
    /**
     * The residual scale of one series, in the series' own units. Zero when the
     * series is too short to fit, and also when it is perfectly smooth.
     */
    fun seriesNoise(values: DoubleArray): Double {
        if (values.size < QUADRATIC_SAMPLES) {
            return 0.0
        }
        val residuals = ArrayList<Double>(values.size)
        for (index in QUADRATIC_HALF_WINDOW until values.lastIndex - QUADRATIC_HALF_WINDOW) {
            val residual =
                (
                    CENTRE_WEIGHT * values[index] +
                        FAR_WEIGHT * (values[index - 2] + values[index + 2]) -
                        NEAR_WEIGHT * (values[index - 1] + values[index + 1])
                ) / FIT_DIVISOR
            if (residual.isFinite()) {
                residuals += abs(residual)
            }
        }
        if (residuals.isEmpty()) {
            return 0.0
        }
        residuals.sort()
        val middle = residuals.size / 2
        val median =
            if (residuals.size % 2 == 1) {
                residuals[middle]
            } else {
                (residuals[middle - 1] + residuals[middle]) / 2.0
            }
        return median / MEDIAN_ABSOLUTE_DEVIATION_FACTOR
    }

    /** The residual scale of two plane coordinates that share one noise source, in metres. */
    fun planeNoise(
        xMeters: DoubleArray,
        yMeters: DoubleArray,
    ): Double = hypot(seriesNoise(xMeters), seriesNoise(yMeters))

    /**
     * A centred average over `2 * halfWindow + 1` samples.
     *
     * The window shrinks at the ends instead of clamping to the last raw value:
     * pinning a noisy first or last sample would inject its noise straight into
     * the first or last step, and on a short total those steps are most of the
     * figure. A one-sided average of a rising line sits below that line, so the
     * smoothed profile starts a little under the raw one - a bias worth a
     * fraction of a metre on a climb and nothing at all on a flat line.
     */
    fun smoothed(
        values: DoubleArray,
        halfWindow: Int,
    ): DoubleArray {
        if (halfWindow < 1 || values.size < MINIMUM_SMOOTHED_SAMPLES) {
            return values.copyOf()
        }
        val prefix = DoubleArray(values.size + 1)
        for (index in values.indices) {
            prefix[index + 1] = prefix[index] + values[index]
        }
        val smoothed = DoubleArray(values.size)
        for (index in values.indices) {
            val from = (index - halfWindow).coerceAtLeast(0)
            val to = (index + halfWindow).coerceAtMost(values.lastIndex)
            smoothed[index] = (prefix[to + 1] - prefix[from]) / (to - from + 1)
        }
        return smoothed
    }

    /**
     * The samples either side of a point that [windowMillis] covers at
     * [intervalMillis] between samples. At least one, so a window narrower than the
     * sampling interval still averages the neighbours it has, and zero only when
     * there is no interval to divide by.
     */
    fun halfWindowSamples(
        windowMillis: Long,
        intervalMillis: Long,
    ): Int {
        if (intervalMillis <= 0) {
            return 0
        }
        return (windowMillis.toDouble() / (2.0 * intervalMillis)).roundToInt().coerceAtLeast(1)
    }

    /** One whole sample each side of the centre: a five-point fit. */
    const val QUADRATIC_HALF_WINDOW: Int = 2

    /** A five-point quadratic needs five samples. */
    private const val QUADRATIC_SAMPLES = QUADRATIC_HALF_WINDOW * 2 + 1

    /** Fewer samples than this and the whole series is the window; the share is the same. */
    private const val MINIMUM_SMOOTHED_SAMPLES = 3

    /**
     * The centred quadratic through five samples evaluates at the middle one with
     * the Savitzky-Golay weights (-3, 12, 17, 12, -3) / 35, so the residual
     * `sample - fit` weights the centre with 35 - 17 = 18, the near neighbours
     * with -2 * 12 = -24 and the far ones with -2 * -3 = 6.
     */
    private const val CENTRE_WEIGHT = 18.0
    private const val NEAR_WEIGHT = 12.0
    private const val FAR_WEIGHT = 3.0
    private const val FIT_DIVISOR = 35.0

    /** A Gaussian's median absolute value is 0.6745 of its standard deviation. */
    private const val MEDIAN_ABSOLUTE_DEVIATION_FACTOR = 0.6745
}
