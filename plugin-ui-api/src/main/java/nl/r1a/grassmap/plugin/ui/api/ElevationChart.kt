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
 * A distance range the user selected on the chart (T-0405).
 *
 * The two ends are distances along the profile, ordered so [endMeters] is the
 * later one. The range is the view's gesture state; the statistics for it come
 * from the analysis engine, not from the chart.
 */
data class ElevationChartRange(
    val startMeters: Double,
    val endMeters: Double,
) {
    /** The ground distance the range covers. */
    val distanceMeters: Double get() = (endMeters - startMeters).coerceAtLeast(0.0)
}

/**
 * The analysis engine's statistics for one [range].
 *
 * Every value is copied from the engine's own range model; the chart only
 * formats them. [durationMillis] is null when the profile has no complete
 * timing.
 */
data class ElevationChartRangeStats(
    val range: ElevationChartRange,
    val distanceMeters: Double,
    val ascentMeters: Double,
    val descentMeters: Double,
    val averageGradePercent: Double,
    val durationMillis: Long? = null,
)

/**
 * One climb of the profile, as the analysis engine found it.
 *
 * [averageGradePercent] is the climb's gain over its own distance. The chart
 * draws the climb where it lies; the analysis panel lists it and tapping it
 * selects [range].
 */
data class ElevationChartClimb(
    val startMeters: Double,
    val endMeters: Double,
    val ascentMeters: Double,
    val averageGradePercent: Double,
    val durationMillis: Long? = null,
) {
    /** The climb's range, for selecting it on the chart. */
    val range: ElevationChartRange get() = ElevationChartRange(startMeters, endMeters)
}

/**
 * One stretch of the chart with the attributes the routing engine's annotate
 * pass gave that part of the line (T-0406).
 *
 * The two ends are distances along the same profile the chart draws, taken from
 * the engine's own annotated segments and their shape ranges, so a stretch
 * covers exactly the part of the axis it describes. [surface] and [wayType] are
 * the engine's own attribute values rather than a second reading of the map,
 * and either is null when the engine had no value for the stretch. The shell
 * groups the values into the round-10 classes; a value it does not know is left
 * out of the strips rather than guessed.
 */
data class ElevationChartStretch(
    val startMeters: Double,
    val endMeters: Double,
    val surface: String? = null,
    val wayType: String? = null,
) {
    /** The ground distance the stretch covers. */
    val distanceMeters: Double get() = (endMeters - startMeters).coerceAtLeast(0.0)
}

/**
 * The chart in the app's state: the engine's samples, the axis they are read
 * along, the selected range with the engine's statistics, the climbs the engine
 * found (T-0405) and the annotated stretches behind the surface and way-type
 * strips (T-0406).
 *
 * [points] is empty when the engine has no profile, and then the shell draws no
 * chart at all instead of an empty frame. [selection] is null when no range is
 * selected; [climbs] is empty when the profile has none. [stretches] is empty
 * when nothing annotated the line, as a GPX track has no attributes of its own.
 */
data class ElevationChartState(
    val points: List<ElevationChartPoint> = emptyList(),
    val axis: ElevationChartAxis = ElevationChartAxis.Distance,
    val selection: ElevationChartRangeStats? = null,
    val climbs: List<ElevationChartClimb> = emptyList(),
    val stretches: List<ElevationChartStretch> = emptyList(),
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
