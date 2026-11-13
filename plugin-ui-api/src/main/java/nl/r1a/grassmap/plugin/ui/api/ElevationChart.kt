package nl.r1a.grassmap.plugin.ui.api

/*
 * The elevation chart's vocabulary (T-0404).
 *
 * The chart is a read-only drawing of the analysis engine's own profile. The
 * points below are copied from that profile as they are: same sample positions,
 * same elevations, no second interpolation. A view maps them onto the chosen
 * axis; nothing here samples the track or the terrain again.
 */

/** The three things the chart can be read along. */
enum class ElevationChartAxis {
    /** Ground distance from the start. */
    Distance,

    /** Wall-clock time since the start, pauses included. */
    Duration,

    /** The clock time of each sample. */
    TimeOfDay,
}

/**
 * One point of the chart.
 *
 * [distanceMeters] and [elevationMeters] come from the engine's profile.
 * [elapsedMillis] and [timeOfDayMillis] are null when the engine had no timing
 * for the sample, which is what makes the two time axes unavailable.
 */
data class ElevationChartPoint(
    val distanceMeters: Double,
    val elevationMeters: Double,
    val elapsedMillis: Long? = null,
    val timeOfDayMillis: Long? = null,
)

/**
 * The chart in the app's state: the engine's samples and the axis they are read
 * along.
 *
 * [points] is empty when the engine has no profile, and then the shell draws no
 * chart at all instead of an empty frame.
 */
data class ElevationChartState(
    val points: List<ElevationChartPoint> = emptyList(),
    val axis: ElevationChartAxis = ElevationChartAxis.Distance,
) {
    /** True when there is a line to draw. */
    val isDrawable: Boolean get() = points.size >= MINIMUM_POINTS

    /** The axes this profile can be read along, in the design's order. */
    val availableAxes: List<ElevationChartAxis>
        get() =
            ELEVATION_AXES.filter { axis ->
                when (axis) {
                    ElevationChartAxis.Distance -> isDrawable && distanceSpan > 0.0
                    ElevationChartAxis.Duration -> hasComplete { it.elapsedMillis }
                    ElevationChartAxis.TimeOfDay -> hasComplete { it.timeOfDayMillis }
                }
            }

    /** The axis to draw with, falling back to distance when the chosen one has no values. */
    val effectiveAxis: ElevationChartAxis
        get() = axis.takeIf { availableAxes.contains(it) } ?: ElevationChartAxis.Distance

    /** The distance from the first sample to the last. */
    val distanceSpan: Double
        get() {
            val first = points.firstOrNull()?.distanceMeters ?: return 0.0
            val last = points.lastOrNull()?.distanceMeters ?: return 0.0
            return (last - first).coerceAtLeast(0.0)
        }

    internal fun hasComplete(select: (ElevationChartPoint) -> Long?): Boolean {
        if (!isDrawable) {
            return false
        }
        val first = select(points.first()) ?: return false
        val last = select(points.last()) ?: return false
        return last > first && points.all { select(it) != null }
    }

    private companion object {
        const val MINIMUM_POINTS = 2
        val ELEVATION_AXES =
            listOf(
                ElevationChartAxis.Distance,
                ElevationChartAxis.Duration,
                ElevationChartAxis.TimeOfDay,
            )
    }
}
