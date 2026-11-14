package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.ui.api.ElevationChartAxis
import nl.r1a.grassmap.plugin.ui.api.ElevationChartPoint
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
 * with the profile behind it. The chart itself only draws; the drag gesture,
 * the selected band and the range and climb rows belong to the shell
 * (T-0405). The surface strips and their stripes are T-0406.
 */

/** One labelled grid line, at a fraction of the plot's width or height. */
data class ElevationChartTick(
    val fraction: Float,
    val label: String,
)

/**
 * What the area under the line is coloured by.
 *
 * The colours are the design's seven-stop scale in both modes. Slope is the
 * design's own scale: the stops are fixed percentages on the hike profile.
 * Altitude reuses those stops over the track's own lowest to highest sample;
 * that min-to-max normalisation is ours, not the design's.
 */
enum class ElevationChartColour {
    /** The design's slope scale, from steep descent to steep climb. */
    Slope,

    /** The same seven stops over the track's own elevation range, low to high. */
    Altitude,
}

/** One filled slice of the area under the line, coloured by its bin on the scale. */
data class ElevationChartFill(
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

/** One colour chip, drawn in the axis chips' own style. */
data class ElevationChartColourChip(
    val colour: ElevationChartColour,
    val label: String,
    val selected: Boolean,
)

/**
 * The scale drawn under the chart, matching the chosen colour mode.
 *
 * [bins] is always the seven stops, lowest first, so the drawing can show the
 * whole ramp even when the profile itself uses fewer of them.
 */
data class ElevationChartLegend(
    val caption: String,
    val bins: List<Int>,
)

/** A drawn point: 0..1 across the plot and 0 at the top..1 at the bottom. */
data class ElevationChartPosition(
    val x: Float,
    val y: Float,
)

/** The selected range as two fractions across the plot, for the selection overlay. */
data class ElevationChartBand(
    val startFraction: Float,
    val endFraction: Float,
)

/** One profile's drawing values, ready for the composable. */
data class ElevationChartContent(
    val title: String,
    val axis: ElevationChartAxis,
    val colour: ElevationChartColour,
    val axes: List<ElevationChartAxisChip>,
    val colours: List<ElevationChartColourChip>,
    val line: List<ElevationChartPosition>,
    val fills: List<ElevationChartFill>,
    val legend: ElevationChartLegend,
    val yTicks: List<ElevationChartTick>,
    val xTicks: List<ElevationChartTick>,
    val minElevationMeters: Double,
    val maxElevationMeters: Double,
    val band: ElevationChartBand? = null,
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
    colour: ElevationChartColour = ElevationChartColour.Slope,
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
        colour = colour,
        axes = axisChips(state, strings),
        colours = colourChips(strings, colour),
        line = line,
        fills = fills(state, line, colour, minElevation, maxElevation),
        legend = legend(colour, minElevation, maxElevation, strings),
        yTicks = elevationTicks(minElevation, maxElevation, strings),
        xTicks = axisTicks(state, axis, strings, locale, zone),
        minElevationMeters = minElevation,
        maxElevationMeters = maxElevation,
        band = band(state),
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

/**
 * The ground distance under [fraction] across the plot, the inverse of
 * [elevationChartX] (T-0405).
 *
 * A drag works on the chosen axis, so the distance is interpolated between the
 * two samples that bracket the touched position. That is the drawn line's own
 * position, not a second reading of the track.
 */
fun elevationDistanceAt(
    state: ElevationChartState,
    fraction: Float,
): Double? {
    val points = state.points
    if (points.size < MINIMUM_POINTS) {
        return null
    }
    val target = fraction.coerceIn(0f, 1f)
    val xs = points.indices.map { index -> elevationChartX(state, index) ?: return null }
    if (target <= xs.first()) {
        return points.first().distanceMeters
    }
    for (index in 0 until points.lastIndex) {
        val start = xs[index]
        val end = xs[index + 1]
        if (target <= end) {
            val span = end - start
            val share = if (span <= 0f) 0f else (target - start) / span
            return points[index].distanceMeters +
                (points[index + 1].distanceMeters - points[index].distanceMeters) * share
        }
    }
    return points.last().distanceMeters
}

/**
 * The fraction where [distanceMeters] sits across the plot, the sampled inverse
 * of [elevationDistanceAt]. The sample distances are the profile's own, so the
 * position follows the drawn line on any axis.
 */
fun elevationChartXAtDistance(
    state: ElevationChartState,
    distanceMeters: Double,
): Float? {
    val points = state.points
    if (points.size < MINIMUM_POINTS) {
        return null
    }
    if (distanceMeters <= points.first().distanceMeters) {
        return elevationChartX(state, 0)
    }
    for (index in 1 until points.size) {
        val before = points[index - 1]
        val after = points[index]
        if (distanceMeters > after.distanceMeters) {
            continue
        }
        val start = elevationChartX(state, index - 1) ?: return null
        val end = elevationChartX(state, index) ?: return null
        val span = after.distanceMeters - before.distanceMeters
        if (span <= 0.0) {
            return end
        }
        val share = ((distanceMeters - before.distanceMeters) / span).toFloat().coerceIn(0f, 1f)
        return start + (end - start) * share
    }
    return elevationChartX(state, points.lastIndex)
}

/** The selected range as plot fractions, or null when there is no usable range. */
private fun band(state: ElevationChartState): ElevationChartBand? {
    val range = state.selection?.range ?: return null
    val start = elevationChartXAtDistance(state, range.startMeters) ?: return null
    val end = elevationChartXAtDistance(state, range.endMeters) ?: return null
    if (end - start <= 0f) {
        return null
    }
    return ElevationChartBand(startFraction = start, endFraction = end)
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

/**
 * The fraction edges that split the track's own elevation range into seven
 * equal spans. The design gives no elevation ramp; dividing the track's own
 * lowest to highest sample into seven is ours.
 */
private val ALTITUDE_BIN_EDGES = (1 until ELEVATION_COLOUR_BINS).map { index -> index.toDouble() / ELEVATION_COLOUR_BINS }

/**
 * The altitude bin for [fraction] of the track's elevation range: 0 is the
 * lowest sample, 6 the highest. A value exactly on an edge stays in the lower
 * bin, the rule the slope scale uses.
 */
fun elevationAltitudeBin(fraction: Double): Int = ALTITUDE_BIN_EDGES.count { edge -> fraction > edge }

/**
 * Where [elevationMeters] sits inside the track's range, as 0..1.
 *
 * A track whose lowest and highest samples are equal has no range, so every
 * step lands on the flat middle instead of dividing by zero.
 */
private fun elevationAltitudeFraction(
    elevationMeters: Double,
    minElevationMeters: Double,
    maxElevationMeters: Double,
): Double {
    val span = maxElevationMeters - minElevationMeters
    if (span <= 0.0) {
        return MIDDLE_SHARE
    }
    return ((elevationMeters - minElevationMeters) / span).coerceIn(0.0, 1.0)
}

private fun fills(
    state: ElevationChartState,
    line: List<ElevationChartPosition>,
    colour: ElevationChartColour,
    minElevationMeters: Double,
    maxElevationMeters: Double,
): List<ElevationChartFill> =
    state.points.zipWithNext().mapIndexedNotNull { index, (from, to) ->
        val start = line[index]
        val end = line[index + 1]
        if (end.x <= start.x) {
            return@mapIndexedNotNull null
        }
        ElevationChartFill(
            startFraction = start.x,
            endFraction = end.x,
            startY = start.y,
            endY = end.y,
            bin = fillBin(colour, from, to, minElevationMeters, maxElevationMeters),
        )
    }

/**
 * The bin of one drawn step. A slope step is classified by its own grade; an
 * altitude step takes the colour of the mean of its two ends, so a filled span
 * carries the height it covers rather than only where it starts.
 */
private fun fillBin(
    colour: ElevationChartColour,
    from: ElevationChartPoint,
    to: ElevationChartPoint,
    minElevationMeters: Double,
    maxElevationMeters: Double,
): Int =
    when (colour) {
        ElevationChartColour.Slope -> {
            elevationSlopeBin(slopePercent(from, to))
        }

        ElevationChartColour.Altitude -> {
            val mean = (from.elevationMeters + to.elevationMeters) / 2.0
            elevationAltitudeBin(elevationAltitudeFraction(mean, minElevationMeters, maxElevationMeters))
        }
    }

private fun slopePercent(
    from: ElevationChartPoint,
    to: ElevationChartPoint,
): Double {
    val stepMeters = to.distanceMeters - from.distanceMeters
    return if (stepMeters > 0.0) {
        (to.elevationMeters - from.elevationMeters) / stepMeters * PERCENT
    } else {
        0.0
    }
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

/**
 * The two colour chips, in the axis chips' own style. The canon draws no colour
 * selector; this row extends the axis chip pattern rather than inventing a
 * control (T-0408).
 */
private fun colourChips(
    strings: Strings,
    selected: ElevationChartColour,
): List<ElevationChartColourChip> =
    ElevationChartColour.entries.map { colour ->
        ElevationChartColourChip(
            colour = colour,
            label = strings.resolve(colourKey(colour)),
            selected = colour == selected,
        )
    }

private fun colourKey(colour: ElevationChartColour): String =
    when (colour) {
        ElevationChartColour.Slope -> COLOUR_SLOPE_KEY
        ElevationChartColour.Altitude -> COLOUR_ALTITUDE_KEY
    }

/**
 * The scale legend for the chosen mode: the design's slope caption, or the
 * track's own lowest and highest samples for altitude.
 */
private fun legend(
    colour: ElevationChartColour,
    minElevationMeters: Double,
    maxElevationMeters: Double,
    strings: Strings,
): ElevationChartLegend =
    ElevationChartLegend(
        caption =
            when (colour) {
                ElevationChartColour.Slope -> {
                    strings.resolve(LEGEND_SLOPE_KEY)
                }

                ElevationChartColour.Altitude -> {
                    strings.resolve(
                        LEGEND_ALTITUDE_KEY,
                        listOf(minElevationMeters.roundToLong().toString(), maxElevationMeters.roundToLong().toString()),
                    )
                }
            },
        bins = (0 until ELEVATION_COLOUR_BINS).toList(),
    )

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

internal fun durationLabel(
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
private const val COLOUR_SLOPE_KEY = "elevation_chart_colour_slope"
private const val COLOUR_ALTITUDE_KEY = "elevation_chart_colour_altitude"
private const val LEGEND_SLOPE_KEY = "elevation_chart_legend_slope"
private const val LEGEND_ALTITUDE_KEY = "elevation_chart_legend_altitude"
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
private const val MIDDLE_SHARE = 0.5
private const val PERCENT = 100.0
private const val WHOLE_NUMBER_TOLERANCE = 0.05
private const val METRES_PER_KILOMETRE = 1_000.0
private const val MILLIS_PER_MINUTE = 60_000.0
private const val MINUTES_PER_HOUR = 60L

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** The number of colours on the scale both modes use. */
const val ELEVATION_COLOUR_BINS: Int = 7
