package nl.r1a.grassmap.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.ui.api.ElevationChartAxis
import nl.r1a.grassmap.plugin.ui.api.ElevationChartState
import java.util.Locale
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
 * The track analysis panel: the name, the four statistics, the meta line and
 * the elevation chart behind the Details toggle. The chart's axis chips stay by
 * the chart. Nothing here moves a control to the top of the screen.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun TrackAnalysisPanel(
    summary: TrackAnalysisSummary,
    chart: ElevationChartState,
    strings: Strings,
    dark: Boolean,
    onAxis: (ElevationChartAxis) -> Unit,
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
                )
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

private val TILE_BACKGROUND = Color(0xFF2E2E2E)

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
