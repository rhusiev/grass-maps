package nl.r1a.grassmap.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.ui.api.ElevationChartAxis
import nl.r1a.grassmap.plugin.ui.api.ElevationChartClimb
import nl.r1a.grassmap.plugin.ui.api.ElevationChartRange
import nl.r1a.grassmap.plugin.ui.api.ElevationChartState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A track's analysis as the panel reads it (T-0404): the four statistics of the
 * round-10 track screen and the elevation chart behind Details.
 *
 * The values are the analysis engine's own; the shell only formats them. A
 * track with no profile still shows its statistics and no chart.
 */

data class TrackAnalysisSummary(
    val name: String? = null,
    val distanceMeters: Double = 0.0,
    val ascentMeters: Double = 0.0,
    val descentMeters: Double = 0.0,
    val movingMillis: Long? = null,
    val durationMillis: Long? = null,
    val averageSpeedMetersPerSecond: Double? = null,
    val maxSpeedMetersPerSecond: Double? = null,
)

/** One statistic of the analysis: a label above a value with its unit. */
data class TrackAnalysisTile(
    val label: String,
    val value: String,
)

/** The analysis panel's text, ready to draw. */
data class TrackAnalysisDisplay(
    val title: String,
    val tiles: List<TrackAnalysisTile>,
    val meta: String?,
    val detailsLabel: String,
)

/**
 * One row of the climb list (T-0405): the ordinal badge, the climb's range as
 * its title, its gain, its average grade and its time.
 *
 * [badgeBin] is the chart's slope bin for the climb's grade, so the badge takes
 * the same palette colour the chart uses for that slope. [range] is what a tap
 * selects on the chart.
 */
data class TrackClimbRow(
    val ordinal: Int,
    val title: String,
    val ascent: String,
    val grade: String,
    val duration: String?,
    val selected: Boolean,
    val badgeBin: Int,
    val range: ElevationChartRange,
)

/**
 * The selected range's row under the chart: its distance range, the engine's
 * gains and time, and the Clear action.
 */
data class TrackRangeRow(
    val text: String,
    val clearLabel: String,
)

/**
 * The analysis panel's text. [expanded] chooses the Details label so the panel
 * and the toggle agree; the tiles and the meta line do not change with it.
 */
fun trackAnalysisDisplay(
    summary: TrackAnalysisSummary,
    strings: Strings,
    locale: Locale = Locale.getDefault(),
    expanded: Boolean = false,
): TrackAnalysisDisplay {
    val tiles =
        listOf(
            TrackAnalysisTile(
                label = strings.resolve(DISTANCE_KEY),
                value = distanceText(summary.distanceMeters, strings, locale),
            ),
            TrackAnalysisTile(
                label = strings.resolve(ASCENT_KEY),
                value = strings.resolve(METRES_KEY, listOf(summary.ascentMeters.roundToLong().toString())),
            ),
            TrackAnalysisTile(
                label = strings.resolve(MOVING_KEY),
                value = summary.movingMillis?.let { millis -> durationText(millis / MILLIS_PER_SECOND, strings) } ?: NO_VALUE,
            ),
            TrackAnalysisTile(
                label = strings.resolve(SPEED_KEY),
                value =
                    summary.averageSpeedMetersPerSecond?.let { speed ->
                        strings.resolve(SPEED_VALUE_KEY, listOf(String.format(locale, "%.1f", speed)))
                    } ?: NO_VALUE,
            ),
        )
    val meta = metaLine(summary, strings, locale)
    return TrackAnalysisDisplay(
        title = summary.name?.takeIf { it.isNotBlank() } ?: strings.resolve(UNTITLED_KEY),
        tiles = tiles,
        meta = meta,
        detailsLabel = strings.resolve(if (expanded) DETAILS_HIDE_KEY else DETAILS_SHOW_KEY),
    )
}

/**
 * The line under the tiles: total time, stopped time, the fastest step and the
 * descent, each only when the engine measured it. The parts join with the
 * design's middle dot.
 */
private fun metaLine(
    summary: TrackAnalysisSummary,
    strings: Strings,
    locale: Locale,
): String? {
    val parts = mutableListOf<String>()
    summary.durationMillis?.let { duration ->
        parts += strings.resolve(TOTAL_KEY, listOf(durationText(duration / MILLIS_PER_SECOND, strings)))
        val moving = summary.movingMillis
        if (moving != null && duration > moving) {
            parts += strings.resolve(STOPPED_KEY, listOf(durationText((duration - moving) / MILLIS_PER_SECOND, strings)))
        }
    }
    summary.maxSpeedMetersPerSecond?.let { speed ->
        parts +=
            strings.resolve(
                MAX_SPEED_KEY,
                listOf(strings.resolve(SPEED_VALUE_KEY, listOf(String.format(locale, "%.1f", speed)))),
            )
    }
    if (summary.descentMeters > 0.0) {
        parts +=
            strings.resolve(
                DESCENT_KEY,
                listOf(summary.descentMeters.roundToLong().toString()),
            )
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(META_SEPARATOR)
}

/**
 * The selected range's row, or null when the chart has no selection. The values
 * are the engine's range statistics; the shell only formats them.
 */
fun trackAnalysisRangeRow(
    state: ElevationChartState,
    strings: Strings,
    locale: Locale = Locale.getDefault(),
): TrackRangeRow? {
    val selection = state.selection ?: return null
    val parts = mutableListOf<String>()
    parts += strings.resolve(ASCENT_VALUE_KEY, listOf(selection.ascentMeters.roundToLong().toString()))
    if (selection.descentMeters > 0.0) {
        parts += strings.resolve(DESCENT_VALUE_KEY, listOf(selection.descentMeters.roundToLong().toString()))
    }
    selection.durationMillis?.let { duration ->
        parts += durationLabel(duration.toDouble(), strings)
    }
    val range = rangeText(selection.range.startMeters, selection.range.endMeters, strings, locale)
    return TrackRangeRow(
        text = (listOf(range) + parts).joinToString(META_SEPARATOR),
        clearLabel = strings.resolve(RANGE_CLEAR_KEY),
    )
}

/**
 * The climb list: one row per climb the engine found, in distance order. The
 * row's title is the climb's own range because a track carries no climb names;
 * the ordinal badge is the design's numbering, not invented text.
 */
fun trackAnalysisClimbRows(
    state: ElevationChartState,
    strings: Strings,
    locale: Locale = Locale.getDefault(),
): List<TrackClimbRow> =
    state.climbs.mapIndexed { index, climb ->
        TrackClimbRow(
            ordinal = index + 1,
            title = rangeText(climb.startMeters, climb.endMeters, strings, locale),
            ascent = strings.resolve(ASCENT_VALUE_KEY, listOf(climb.ascentMeters.roundToLong().toString())),
            grade = strings.resolve(GRADE_VALUE_KEY, listOf(climb.averageGradePercent.roundToLong().toString())),
            duration = climb.durationMillis?.let { duration -> durationLabel(duration.toDouble(), strings) },
            selected = isSelected(state, climb),
            badgeBin = elevationSlopeBin(climb.averageGradePercent),
            range = climb.range,
        )
    }

private fun isSelected(
    state: ElevationChartState,
    climb: ElevationChartClimb,
): Boolean {
    val range = state.selection?.range ?: return false
    return abs(range.startMeters - climb.startMeters) <= RANGE_MATCH_METERS &&
        abs(range.endMeters - climb.endMeters) <= RANGE_MATCH_METERS
}

/**
 * The design's distance range: both ends in kilometres when the range reaches a
 * kilometre, otherwise both in metres, with one unit at the end.
 */
private fun rangeText(
    startMeters: Double,
    endMeters: Double,
    strings: Strings,
    locale: Locale,
): String =
    if (endMeters >= METRES_PER_KILOMETRE) {
        strings.resolve(
            RANGE_KILOMETRES_KEY,
            listOf(kilometres(startMeters, locale), kilometres(endMeters, locale)),
        )
    } else {
        strings.resolve(
            RANGE_METRES_KEY,
            listOf(startMeters.roundToLong().toString(), endMeters.roundToLong().toString()),
        )
    }

private fun kilometres(
    metres: Double,
    locale: Locale,
): String = String.format(locale, "%.1f", metres / METRES_PER_KILOMETRE)

/**
 * The track analysis panel: the name, the four statistics, the meta line and
 * the elevation chart behind the Details toggle. With [onRange] the chart is a
 * range selector, and the selected range's row and the climb list sit under it
 * (T-0405). Nothing here moves a control to the top of the screen.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun TrackAnalysisPanel(
    summary: TrackAnalysisSummary,
    chart: ElevationChartState,
    strings: Strings,
    dark: Boolean,
    onAxis: (ElevationChartAxis) -> Unit,
    onRange: (ElevationChartRange?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(summary.name) { mutableStateOf(false) }
    val display =
        trackAnalysisDisplay(
            summary = summary,
            strings = strings,
            expanded = expanded,
        )
    Column(
        modifier =
            modifier
                .padding(PANEL_PADDING_DP.dp)
                .widthIn(max = PANEL_MAX_WIDTH_DP.dp)
                .background(ROUTE_PANEL_BACKGROUND, RoundedCornerShape(PANEL_CORNER_DP.dp))
                .padding(PANEL_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(PANEL_ROW_GAP_DP.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f))
            BasicText(
                text = display.title,
                style =
                    TextStyle(
                        color = ROUTE_VALUE_COLOR,
                        fontSize = TITLE_FONT_SP.sp,
                        fontWeight = FontWeight.Bold,
                    ),
            )
            Box(Modifier.weight(1f))
            BasicText(
                text = CLOSE_GLYPH,
                style = TextStyle(color = ROUTE_VALUE_COLOR, fontSize = CONTROL_FONT_SP.sp),
                modifier =
                    Modifier
                        .semantics { contentDescription = strings.resolve(TRACK_CLOSE_KEY) }
                        .background(ROUTE_CHIP_COLOR, CircleShape)
                        .clickable(onClick = onClose)
                        .padding(CLOSE_PADDING_DP.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PANEL_ROW_GAP_DP.dp),
        ) {
            display.tiles.forEach { tile ->
                TrackTile(tile = tile, modifier = Modifier.weight(1f))
            }
        }
        display.meta?.let { meta ->
            BasicText(
                text = meta,
                style = TextStyle(color = ROUTE_STATUS_COLOR, fontSize = META_FONT_SP.sp),
            )
        }
        if (chart.isDrawable) {
            AnalysisDetailsToggle(
                expanded = expanded,
                strings = strings,
                onClick = { expanded = !expanded },
            )
            if (expanded) {
                ElevationChart(
                    state = chart,
                    strings = strings,
                    dark = dark,
                    onAxis = onAxis,
                    onRange = onRange,
                )
                val rangeRow = trackAnalysisRangeRow(chart, strings)
                if (rangeRow != null) {
                    RangeRow(row = rangeRow, onClear = { onRange(null) })
                }
                val climbs = trackAnalysisClimbRows(chart, strings)
                if (climbs.isNotEmpty()) {
                    BasicText(
                        text = strings.resolve(CLIMBS_TITLE_KEY),
                        style =
                            TextStyle(
                                color = ROUTE_STATUS_COLOR,
                                fontSize = CLIMBS_TITLE_FONT_SP.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        modifier = Modifier.padding(top = CLIMBS_TITLE_TOP_DP.dp),
                    )
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = CLIMB_LIST_MAX_HEIGHT_DP.dp)
                                .verticalScroll(rememberScrollState()),
                    ) {
                        climbs.forEach { row ->
                            ClimbRow(row = row, dark = dark, onClick = { onRange(row.range) })
                        }
                    }
                }
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun TrackTile(
    tile: TrackAnalysisTile,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .background(TILE_BACKGROUND, RoundedCornerShape(TILE_CORNER_DP.dp))
                .padding(horizontal = TILE_PADDING_HORIZONTAL_DP.dp, vertical = TILE_PADDING_VERTICAL_DP.dp),
        verticalArrangement = Arrangement.spacedBy(TILE_GAP_DP.dp),
    ) {
        BasicText(
            text = tile.label,
            style = TextStyle(color = ROUTE_STATUS_COLOR, fontSize = TILE_LABEL_FONT_SP.sp),
        )
        BasicText(
            text = tile.value,
            style =
                TextStyle(
                    color = ROUTE_VALUE_COLOR,
                    fontSize = TILE_VALUE_FONT_SP.sp,
                    fontWeight = FontWeight.Bold,
                ),
        )
    }
}

/** The selected range's line and its Clear action, as the design places them under the chart. */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun RangeRow(
    row: TrackRangeRow,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
    ) {
        BasicText(
            text = row.text,
            modifier = Modifier.weight(1f),
            style = TextStyle(color = ROUTE_STATUS_COLOR, fontSize = ROW_FONT_SP.sp),
        )
        BasicText(
            text = row.clearLabel,
            style =
                TextStyle(
                    color = ROUTE_ACTION_COLOR,
                    fontSize = ROW_FONT_SP.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            modifier = Modifier.clickable(onClick = onClear).padding(horizontal = CLEAR_PADDING_HORIZONTAL_DP.dp),
        )
    }
}

/**
 * One climb row: the design's badge, the climb's range, its gain, its grade and
 * its time. A tapped row selects its range on the chart.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun ClimbRow(
    row: TrackClimbRow,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val palette = elevationChartPalette(dark)
    val badge = palette.slope[row.badgeBin.coerceIn(0, palette.slope.lastIndex)]
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    if (row.selected) CLIMB_SELECTED_BACKGROUND else Color.Transparent,
                    RoundedCornerShape(CLIMB_CORNER_DP.dp),
                ).clickable(onClick = onClick)
                .padding(horizontal = CLIMB_PADDING_HORIZONTAL_DP.dp, vertical = CLIMB_PADDING_VERTICAL_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CLIMB_GAP_DP.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(CLIMB_BADGE_DP.dp)
                    .background(badge, RoundedCornerShape(CLIMB_BADGE_CORNER_DP.dp)),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = row.ordinal.toString(),
                style =
                    TextStyle(
                        color = elevationBadgeInk(badge),
                        fontSize = CLIMB_BADGE_FONT_SP.sp,
                        fontWeight = FontWeight.Bold,
                    ),
            )
        }
        BasicText(
            text = row.title,
            modifier = Modifier.weight(1f),
            style = TextStyle(color = ROUTE_VALUE_COLOR, fontSize = CLIMB_FONT_SP.sp, fontWeight = FontWeight.SemiBold),
        )
        BasicText(
            text = row.ascent,
            modifier = Modifier.width(CLIMB_ASCENT_WIDTH_DP.dp),
            style = TextStyle(color = ROUTE_VALUE_COLOR, fontSize = CLIMB_FONT_SP.sp),
        )
        BasicText(
            text = row.grade,
            modifier = Modifier.width(CLIMB_GRADE_WIDTH_DP.dp),
            style = TextStyle(color = ROUTE_VALUE_COLOR, fontSize = CLIMB_FONT_SP.sp),
        )
        row.duration?.let { duration ->
            BasicText(
                text = duration,
                modifier = Modifier.width(CLIMB_TIME_WIDTH_DP.dp),
                style =
                    TextStyle(
                        color = ROUTE_STATUS_COLOR,
                        fontSize = CLIMB_FONT_SP.sp,
                        textAlign = TextAlign.End,
                    ),
            )
        }
    }
}

private val TILE_BACKGROUND = Color(0xFF2E2E2E)

/** The tone a selected climb row takes on the panel's dark card. */
private val CLIMB_SELECTED_BACKGROUND = Color(0x1FFFFFFF)

private const val PANEL_CORNER_DP = 12
private const val PANEL_PADDING_DP = 12
private const val PANEL_MAX_WIDTH_DP = 520
private const val PANEL_ROW_GAP_DP = 8
private const val TILE_CORNER_DP = 12
private const val TILE_PADDING_HORIZONTAL_DP = 10
private const val TILE_PADDING_VERTICAL_DP = 8
private const val TILE_GAP_DP = 2
private const val TITLE_FONT_SP = 17
private const val TILE_LABEL_FONT_SP = 11
private const val TILE_VALUE_FONT_SP = 16
private const val META_FONT_SP = 12
private const val CONTROL_FONT_SP = 18
private const val CLOSE_PADDING_DP = 6
private const val META_SEPARATOR = " · "
private const val NO_VALUE = "-"
private const val MILLIS_PER_SECOND = 1_000.0
private const val CLOSE_GLYPH = "\u00D7"
private const val ROW_FONT_SP = 12
private const val ROW_GAP_DP = 8
private const val CLEAR_PADDING_HORIZONTAL_DP = 6
private const val CLIMBS_TITLE_FONT_SP = 11
private const val CLIMBS_TITLE_TOP_DP = 4
private const val CLIMB_LIST_MAX_HEIGHT_DP = 192
private const val CLIMB_CORNER_DP = 10
private const val CLIMB_PADDING_HORIZONTAL_DP = 8
private const val CLIMB_PADDING_VERTICAL_DP = 4
private const val CLIMB_GAP_DP = 9
private const val CLIMB_BADGE_DP = 20
private const val CLIMB_BADGE_CORNER_DP = 6
private const val CLIMB_BADGE_FONT_SP = 11
private const val CLIMB_FONT_SP = 12
private const val CLIMB_ASCENT_WIDTH_DP = 52
private const val CLIMB_GRADE_WIDTH_DP = 32
private const val CLIMB_TIME_WIDTH_DP = 46
private const val RANGE_MATCH_METERS = 1.0
private const val METRES_PER_KILOMETRE = 1_000.0

private const val DISTANCE_KEY = "track_analysis_distance"
private const val ASCENT_KEY = "track_analysis_ascent"
private const val MOVING_KEY = "track_analysis_moving"
private const val SPEED_KEY = "track_analysis_speed"
private const val SPEED_VALUE_KEY = "track_analysis_speed_value"
private const val METRES_KEY = "track_analysis_metres"
private const val TOTAL_KEY = "track_analysis_total"
private const val STOPPED_KEY = "track_analysis_stopped"
private const val MAX_SPEED_KEY = "track_analysis_max_speed"
private const val DESCENT_KEY = "track_analysis_descent"
private const val UNTITLED_KEY = "track_analysis_untitled"
private const val TRACK_CLOSE_KEY = "track_analysis_close"
private const val DETAILS_SHOW_KEY = "elevation_chart_details_show"
private const val DETAILS_HIDE_KEY = "elevation_chart_details_hide"
private const val RANGE_KILOMETRES_KEY = "elevation_chart_range_kilometres"
private const val RANGE_METRES_KEY = "elevation_chart_range_metres"
private const val ASCENT_VALUE_KEY = "elevation_chart_range_ascent"
private const val DESCENT_VALUE_KEY = "elevation_chart_range_descent"
private const val GRADE_VALUE_KEY = "elevation_chart_range_grade"
private const val RANGE_CLEAR_KEY = "elevation_chart_range_clear"
private const val CLIMBS_TITLE_KEY = "elevation_chart_climbs_title"
