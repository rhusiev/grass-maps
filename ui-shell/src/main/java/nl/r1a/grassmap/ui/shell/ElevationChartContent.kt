package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.ui.api.ElevationChartAxis
import nl.r1a.grassmap.plugin.ui.api.ElevationChartState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

/*
 * The elevation chart's drawing values (T-0404).
 *
 * Everything here is derived from ElevationChartState, which holds the
 * analysis engine's own samples. A chart point keeps its engine elevation and
 * only gains a position along the chosen axis, so the drawing cannot disagree
 * with the profile behind it. The chart is read-only: no drag, no tap, no
 * selection yet. The surface strips and climb markers of the round-10 design
 * belong to T-0405 and T-0406.
 */

/** One labelled grid line, at a fraction of the plot's width or height. */
data class ElevationChartTick(
    val fraction: Float,
    val label: String,
)

/** One filled slice of the area under the line, coloured by the slope it covers. */
data class ElevationChartSlope(
    val startFraction: Float,
    val endFraction: Float,
    val startY: Float,
    val endY: Float,
    val bin: Int,
)

/** One axis chip. The selected chip stays beside the chart, never at the top. */
data class ElevationChartAxisChip(
    val axis: ElevationChartAxis,
    val label: String,
    val selected: Boolean,
)

/** A drawn point: 0..1 across the plot and 0 at the top..1 at the bottom. */
data class ElevationChartPosition(
    val x: Float,
    val y: Float,
)

/** One profile's drawing values, ready for the composable. */
data class ElevationChartContent(
    val title: String,
    val axis: ElevationChartAxis,
    val axes: List<ElevationChartAxisChip>,
    val line: List<ElevationChartPosition>,
    val slopes: List<ElevationChartSlope>,
    val yTicks: List<ElevationChartTick>,
    val xTicks: List<ElevationChartTick>,
    val minElevationMeters: Double,
    val maxElevationMeters: Double,
)

/**
 * The chart for [state], or null when the engine has no profile or the profile
 * cannot be read along any axis. Null means no chart: the caller draws nothing,
 * not an empty frame.
 */
fun elevationChartContent(
    state: ElevationChartState,
    strings: Strings,
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
): ElevationChartContent? {
    if (!state.isDrawable) {
        return null
    }
    val axis = state.effectiveAxis
    if (!state.availableAxes.contains(axis)) {
        return null
    }
    val fractions = state.points.indices.map { index -> elevationChartX(state, index) ?: 0f }
    if (fractions.zipWithNext().any { (first, second) -> second < first }) {
        return null
    }
    val minElevation = state.points.minOf { it.elevationMeters }
    val maxElevation = state.points.maxOf { it.elevationMeters }
    val line =
        state.points.mapIndexed { index, point ->
            ElevationChartPosition(
                x = fractions[index],
                y = elevationFraction(point.elevationMeters, minElevation, maxElevation),
            )
        }
    return ElevationChartContent(
        title = strings.resolve(CHART_TITLE_KEY),
        axis = axis,
        axes = axisChips(state, strings),
        line = line,
        slopes = slopes(state, line),
        yTicks = elevationTicks(minElevation, maxElevation, strings),
        xTicks = axisTicks(state, axis, strings, locale, zone),
        minElevationMeters = minElevation,
        maxElevationMeters = maxElevation,
    )
}

/**
 * Where sample [index] sits across the chart on the chosen axis, as 0..1.
 *
 * The two time axes use the sample's own elapsed time or timestamp, so they
 * plot the engine's timing rather than a second interpolation of it.
 */
fun elevationChartX(
    state: ElevationChartState,
    index: Int,
): Float? {
    val points = state.points
    if (index !in points.indices || points.size < MINIMUM_POINTS) {
        return null
    }
    val axis = state.effectiveAxis
    if (!state.availableAxes.contains(axis)) {
        return null
    }
    val value =
        when (axis) {
            ElevationChartAxis.Distance -> points[index].distanceMeters
            ElevationChartAxis.Duration -> points[index].elapsedMillis?.toDouble() ?: return null
            ElevationChartAxis.TimeOfDay -> points[index].timeOfDayMillis?.toDouble() ?: return null
        }
    val (start, end) = axisRange(state) ?: return null
    val span = end - start
    if (span <= 0.0) {
        return null
    }
    return ((value - start) / span).toFloat().coerceIn(0f, 1f)
}

/** The first and last value of [state]'s effective axis, null when one is missing. */
private fun axisRange(state: ElevationChartState): Pair<Double, Double>? {
    val first = state.points.firstOrNull() ?: return null
    val last = state.points.lastOrNull() ?: return null
    return when (state.effectiveAxis) {
        ElevationChartAxis.Distance -> {
            first.distanceMeters to last.distanceMeters
        }

        ElevationChartAxis.Duration -> {
            val start = first.elapsedMillis ?: return null
            val end = last.elapsedMillis ?: return null
            start.toDouble() to end.toDouble()
        }

        ElevationChartAxis.TimeOfDay -> {
            val start = first.timeOfDayMillis ?: return null
            val end = last.timeOfDayMillis ?: return null
            start.toDouble() to end.toDouble()
        }
    }
}

/** The design's slope scale, in percent, on the hike profile. */
private val SLOPE_BIN_EDGES = listOf(-15.0, -8.0, -3.0, 3.0, 8.0, 15.0)

/** The slope bin for one step: 0 is the steepest descent, 6 the steepest climb. */
fun elevationSlopeBin(slopePercent: Double): Int = SLOPE_BIN_EDGES.count { edge -> slopePercent > edge }

private fun slopes(
    state: ElevationChartState,
    line: List<ElevationChartPosition>,
): List<ElevationChartSlope> =
    state.points.zipWithNext().mapIndexedNotNull { index, (from, to) ->
        val start = line[index]
        val end = line[index + 1]
        if (end.x <= start.x) {
            return@mapIndexedNotNull null
        }
        val stepMeters = to.distanceMeters - from.distanceMeters
        val slope =
            if (stepMeters > 0.0) {
                (to.elevationMeters - from.elevationMeters) / stepMeters * PERCENT
            } else {
                0.0
            }
        ElevationChartSlope(
            startFraction = start.x,
            endFraction = end.x,
            startY = start.y,
            endY = end.y,
            bin = elevationSlopeBin(slope),
        )
    }

private fun elevationFraction(
    elevationMeters: Double,
    minElevationMeters: Double,
    maxElevationMeters: Double,
): Float {
    val span = maxElevationMeters - minElevationMeters
    if (span <= 0.0) {
        return MIDDLE_FRACTION
    }
    return (1.0 - (elevationMeters - minElevationMeters) / span).toFloat().coerceIn(0f, 1f)
}

private fun axisChips(
    state: ElevationChartState,
    strings: Strings,
): List<ElevationChartAxisChip> {
    val available = state.availableAxes
    val selected = state.effectiveAxis
    return available.map { axis ->
        ElevationChartAxisChip(
            axis = axis,
            label = strings.resolve(axisKey(axis)),
            selected = axis == selected,
        )
    }
}

private fun axisKey(axis: ElevationChartAxis): String =
    when (axis) {
        ElevationChartAxis.Distance -> AXIS_DISTANCE_KEY
        ElevationChartAxis.Duration -> AXIS_DURATION_KEY
        ElevationChartAxis.TimeOfDay -> AXIS_TIME_OF_DAY_KEY
    }

private fun elevationTicks(
    minElevationMeters: Double,
    maxElevationMeters: Double,
    strings: Strings,
): List<ElevationChartTick> {
    val steps = COUNT_BETWEEN_TICKS
    return (0..steps).map { index ->
        val fraction = index / steps.toFloat()
        val elevation = maxElevationMeters - (maxElevationMeters - minElevationMeters) * fraction
        ElevationChartTick(
            fraction = fraction,
            label = strings.resolve(METRES_KEY, listOf(elevation.roundToLong().toString())),
        )
    }
}

private fun axisTicks(
    state: ElevationChartState,
    axis: ElevationChartAxis,
    strings: Strings,
    locale: Locale,
    zone: ZoneId,
): List<ElevationChartTick> {
    val (start, end) = axisRange(state) ?: return emptyList()
    val span = end - start
    if (span <= 0.0) {
        return emptyList()
    }
    val step = tickStep(axis, span)
    val ticks = mutableListOf<ElevationChartTick>()
    ticks +=
        ElevationChartTick(
            fraction = 0f,
            label = tickLabel(axis, start, step, strings, locale, zone),
        )
    var value = ceil(start / step) * step
    if (value == start) {
        value += step
    }
    while (value < end - step * MINIMUM_TICK_SHARE) {
        ticks +=
            ElevationChartTick(
                fraction = ((value - start) / span).toFloat().coerceIn(0f, 1f),
                label = tickLabel(axis, value, step, strings, locale, zone),
            )
        value += step
    }
    if (ticks.last().fraction < 1f - MINIMUM_TICK_SHARE) {
        ticks +=
            ElevationChartTick(
                fraction = 1f,
                label = tickLabel(axis, end, step, strings, locale, zone),
            )
    }
    if (ticks.size > MAXIMUM_TICKS) {
        return evenTicks(axis, start, span, strings, locale, zone)
    }
    return ticks
}

/**
 * A tick every whole step of the axis, chosen so the chart carries a handful of
 * labels instead of a crowded ruler.
 */
private fun tickStep(
    axis: ElevationChartAxis,
    span: Double,
): Double {
    val candidates = tickCandidates(axis)
    return candidates.firstOrNull { span / it <= MAXIMUM_TICK_SPACING } ?: candidates.last()
}

private fun tickCandidates(axis: ElevationChartAxis): List<Double> =
    when (axis) {
        ElevationChartAxis.Distance -> {
            listOf(
                METRES_PER_KILOMETRE / 10,
                METRES_PER_KILOMETRE / 5,
                METRES_PER_KILOMETRE / 2,
                METRES_PER_KILOMETRE,
                2 * METRES_PER_KILOMETRE,
                5 * METRES_PER_KILOMETRE,
                10 * METRES_PER_KILOMETRE,
            )
        }

        ElevationChartAxis.Duration -> {
            listOf(
                MILLIS_PER_MINUTE * 15,
                MILLIS_PER_MINUTE * 30,
                MILLIS_PER_MINUTE * 60,
                MILLIS_PER_MINUTE * 120,
                MILLIS_PER_MINUTE * 240,
            )
        }

        ElevationChartAxis.TimeOfDay -> {
            listOf(
                MILLIS_PER_MINUTE * 15,
                MILLIS_PER_MINUTE * 30,
                MILLIS_PER_MINUTE * 60,
                MILLIS_PER_MINUTE * 120,
                MILLIS_PER_MINUTE * 180,
            )
        }
    }

private fun evenTicks(
    axis: ElevationChartAxis,
    start: Double,
    span: Double,
    strings: Strings,
    locale: Locale,
    zone: ZoneId,
): List<ElevationChartTick> =
    (0..COUNT_BETWEEN_TICKS).map { index ->
        val fraction = index / COUNT_BETWEEN_TICKS.toFloat()
        val value = start + span * fraction
        ElevationChartTick(
            fraction = fraction,
            label = tickLabel(axis, value, span / COUNT_BETWEEN_TICKS, strings, locale, zone),
        )
    }

private fun tickLabel(
    axis: ElevationChartAxis,
    value: Double,
    step: Double,
    strings: Strings,
    locale: Locale,
    zone: ZoneId,
): String =
    when (axis) {
        ElevationChartAxis.Distance -> {
            distanceLabel(value, step, strings, locale)
        }

        ElevationChartAxis.Duration -> {
            durationLabel(value, strings)
        }

        ElevationChartAxis.TimeOfDay -> {
            CLOCK_FORMAT.format(Instant.ofEpochMilli(value.toLong()).atZone(zone))
        }
    }

private fun distanceLabel(
    metres: Double,
    step: Double,
    strings: Strings,
    locale: Locale,
): String =
    if (step < METRES_PER_KILOMETRE) {
        strings.resolve(METRES_KEY, listOf(metres.roundToLong().toString()))
    } else {
        val kilometres = metres / METRES_PER_KILOMETRE
        val text =
            if (abs(kilometres - kilometres.roundToLong()) < WHOLE_NUMBER_TOLERANCE) {
                kilometres.roundToLong().toString()
            } else {
                String.format(locale, "%.1f", kilometres)
            }
        strings.resolve(KILOMETRES_KEY, listOf(text))
    }

private fun durationLabel(
    milliseconds: Double,
    strings: Strings,
): String {
    val totalMinutes = (milliseconds / MILLIS_PER_MINUTE).roundToLong().coerceAtLeast(0L)
    val hours = totalMinutes / MINUTES_PER_HOUR
    val minutes = totalMinutes % MINUTES_PER_HOUR
    return when {
        hours == 0L -> strings.resolve(MINUTES_KEY, listOf(minutes.toString()))
        minutes == 0L -> strings.resolve(HOURS_KEY, listOf(hours.toString()))
        else -> strings.resolve(HOURS_MINUTES_KEY, listOf(hours.toString(), minutes.toString()))
    }
}

private const val CHART_TITLE_KEY = "elevation_chart_title"
private const val AXIS_DISTANCE_KEY = "elevation_chart_axis_distance"
private const val AXIS_DURATION_KEY = "elevation_chart_axis_duration"
private const val AXIS_TIME_OF_DAY_KEY = "elevation_chart_axis_time_of_day"
private const val METRES_KEY = "elevation_chart_metres"
private const val KILOMETRES_KEY = "elevation_chart_kilometres"
private const val MINUTES_KEY = "elevation_chart_minutes"
private const val HOURS_KEY = "elevation_chart_hours"
private const val HOURS_MINUTES_KEY = "elevation_chart_hours_minutes"

private const val COUNT_BETWEEN_TICKS = 3
private const val MAXIMUM_TICKS = 6
private const val MAXIMUM_TICK_SPACING = 5.0
private const val MINIMUM_TICK_SHARE = 0.04
private const val MINIMUM_POINTS = 2
private const val MIDDLE_FRACTION = 0.5f
private const val PERCENT = 100.0
private const val WHOLE_NUMBER_TOLERANCE = 0.05
private const val METRES_PER_KILOMETRE = 1_000.0
private const val MILLIS_PER_MINUTE = 60_000.0
private const val MINUTES_PER_HOUR = 60L

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** The number of slope colours on the scale. */
const val ELEVATION_SLOPE_BINS: Int = 7
