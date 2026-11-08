package nl.r1a.grassmap.plugins.map.controls

import nl.r1a.grassmap.plugin.api.model.UnitSystem

/** The unit one scale-bar reading is labelled in. */
enum class ScaleBarUnit {
    Metres,
    Kilometres,
    Feet,
    Miles,
}

/**
 * One scale-bar reading: the ground distance it spans, the width that distance
 * occupies in dp, and the value and unit the label shows (T-0107).
 */
data class ScaleBarReading(
    val distanceMeters: Double,
    val widthDp: Double,
    val value: Double,
    val unit: ScaleBarUnit,
)

/**
 * Chooses the largest round distance that fits a scale bar and sizes the bar.
 *
 * The input is the map's own ground scale in metres per dp, so the bar width is
 * `distance / metersPerDp` and no density conversion happens here. Round
 * distances step 1, 2 and 5 per decade. Metric labels metres below a kilometre
 * and kilometres from one; imperial labels feet below a mile and miles from
 * one, so a bar never reads more than 5280 ft.
 */
object ScaleBar {
    /** The longest bar the layout gives the control. */
    const val MAX_WIDTH_DP: Double = 96.0

    /**
     * The reading for [metersPerDp], or null when the scale is not a usable
     * positive number. The result is null as well when even the smallest round
     * distance is wider than [maxWidthDp], so a caller can hide the bar instead
     * of overflowing the control it sits in.
     */
    fun reading(
        metersPerDp: Double,
        units: UnitSystem,
        maxWidthDp: Double = MAX_WIDTH_DP,
    ): ScaleBarReading? {
        if (!metersPerDp.isFinite() || metersPerDp <= 0.0) {
            return null
        }
        if (!maxWidthDp.isFinite() || maxWidthDp <= 0.0) {
            return null
        }
        val steps = if (units == UnitSystem.Metric) metricSteps else imperialSteps
        val step = largestThatFits(steps, metersPerDp * maxWidthDp)
        val widthDp = step.meters / metersPerDp
        if (!widthDp.isFinite() || widthDp > maxWidthDp + WIDTH_EPSILON_DP) {
            return null
        }
        return ScaleBarReading(
            distanceMeters = step.meters,
            widthDp = widthDp.coerceAtMost(maxWidthDp),
            value = step.value,
            unit = step.unit,
        )
    }

    private fun largestThatFits(
        steps: List<Step>,
        maxMeters: Double,
    ): Step {
        var best = steps.first()
        for (step in steps) {
            if (step.meters > maxMeters) {
                break
            }
            best = step
        }
        return best
    }

    private data class Step(
        val meters: Double,
        val value: Double,
        val unit: ScaleBarUnit,
    )

    private const val DECADE_FACTOR = 10.0
    private const val SMALLEST_DECADE = 0.1
    private const val METRIC_DECADES = 8
    private const val FOOT_DECADES = 4
    private const val MILE_DECADES = 5
    private const val WIDTH_EPSILON_DP = 1e-6
    private val DECADE_FACTORS = listOf(1.0, 2.0, 5.0)
    private const val METRES_PER_KILOMETRE = 1_000.0
    private const val METRES_PER_FOOT = 0.3048
    private const val METRES_PER_MILE = 1_609.344
    private const val FEET_PER_MILE = 5_280.0

    private val metricSteps: List<Step> =
        buildList {
            var decade = SMALLEST_DECADE
            repeat(METRIC_DECADES) {
                for (factor in DECADE_FACTORS) {
                    val meters = decade * factor
                    add(stepMeters(meters))
                }
                decade *= DECADE_FACTOR
            }
        }

    private val imperialSteps: List<Step> =
        buildList {
            var decade = 1.0
            repeat(FOOT_DECADES) {
                for (factor in DECADE_FACTORS) {
                    val feet = decade * factor
                    if (feet < FEET_PER_MILE) {
                        add(Step(feet * METRES_PER_FOOT, feet, ScaleBarUnit.Feet))
                    }
                }
                decade *= DECADE_FACTOR
            }
            var miles = 1.0
            repeat(MILE_DECADES) {
                for (factor in DECADE_FACTORS) {
                    val value = miles * factor
                    add(Step(value * METRES_PER_MILE, value, ScaleBarUnit.Miles))
                }
                miles *= DECADE_FACTOR
            }
        }

    private fun stepMeters(meters: Double): Step =
        if (meters >= METRES_PER_KILOMETRE) {
            Step(meters, meters / METRES_PER_KILOMETRE, ScaleBarUnit.Kilometres)
        } else {
            Step(meters, meters, ScaleBarUnit.Metres)
        }
}
