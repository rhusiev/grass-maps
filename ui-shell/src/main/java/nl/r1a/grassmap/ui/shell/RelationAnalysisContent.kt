package nl.r1a.grassmaps.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmaps.plugin.api.model.RouteNetwork
import nl.r1a.grassmaps.plugin.api.model.UnitSystem
import nl.r1a.grassmaps.plugin.api.service.Strings
import nl.r1a.grassmaps.plugin.api.unit.UnitText
import nl.r1a.grassmaps.plugin.ui.api.ElevationChartAxis
import nl.r1a.grassmaps.plugin.ui.api.ElevationChartState
import java.util.Locale

/**
 * A route relation's chart as the panel reads it (T-0420): the relation's
 * name, its route kind and network, and the analysis of its member ways.
 *
 * The values are the analysis engine's own. The panel formats them and reuses
 * [ElevationChart]; it never measures the relation or samples the terrain.
 */
data class RelationAnalysisSummary(
    val name: String? = null,
    val network: RouteNetwork = RouteNetwork.HikingLocal,
    val ref: String? = null,
    val distanceMeters: Double = 0.0,
    val ascentMeters: Double = 0.0,
    val descentMeters: Double = 0.0,
)

/** The relation panel's text, ready to draw. */
data class RelationAnalysisDisplay(
    val title: String,
    val subtitle: String,
    val stats: String,
    val reference: String?,
)

/**
 * The relation panel's text. The subtitle names the network tier and the route
 * kind together, as the round-6 relation chart does. The stats line carries the
 * engine's distance and, only when the engine found a gain, its ascent and
 * descent.
 */
fun relationAnalysisDisplay(
    summary: RelationAnalysisSummary,
    strings: Strings,
    locale: Locale = Locale.getDefault(),
    units: UnitSystem = UnitSystem.Metric,
): RelationAnalysisDisplay {
    val parts = mutableListOf(UnitText.distance(summary.distanceMeters, units, strings, locale))
    if (summary.ascentMeters > 0.0) {
        parts += UnitText.elevationLabel(summary.ascentMeters, units, strings, RELATION_ASCENT_KEY, RELATION_ASCENT_FEET_KEY)
    }
    if (summary.descentMeters > 0.0) {
        parts += UnitText.elevationLabel(summary.descentMeters, units, strings, RELATION_DESCENT_KEY, RELATION_DESCENT_FEET_KEY)
    }
    return RelationAnalysisDisplay(
        title = summary.name?.takeIf { it.isNotBlank() } ?: strings.resolve(RELATION_UNTITLED_KEY),
        subtitle = strings.resolve(subtitleKey(summary.network)),
        stats = parts.joinToString(RELATION_STATS_SEPARATOR),
        reference = summary.ref?.takeIf { it.isNotBlank() }?.let { ref -> strings.resolve(RELATION_REF_KEY, listOf(ref)) },
    )
}

private fun subtitleKey(network: RouteNetwork): String =
    when (network) {
        RouteNetwork.HikingInternational -> RELATION_HIKING_INTERNATIONAL_KEY
        RouteNetwork.HikingNational -> RELATION_HIKING_NATIONAL_KEY
        RouteNetwork.HikingRegional -> RELATION_HIKING_REGIONAL_KEY
        RouteNetwork.HikingLocal -> RELATION_HIKING_LOCAL_KEY
        RouteNetwork.CyclingInternational -> RELATION_CYCLING_INTERNATIONAL_KEY
        RouteNetwork.CyclingNational -> RELATION_CYCLING_NATIONAL_KEY
        RouteNetwork.CyclingRegional -> RELATION_CYCLING_REGIONAL_KEY
        RouteNetwork.CyclingLocal -> RELATION_CYCLING_LOCAL_KEY
    }

/**
 * The relation chart panel: the relation's identity and the analysis of its
 * member ways, on the same card and with the same chart as a track's analysis.
 * A relation has no timing of its own, so only the distance axis is available
 * and the chart draws the engine's profile directly, without a Details toggle.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun RelationAnalysisPanel(
    summary: RelationAnalysisSummary,
    chart: ElevationChartState,
    strings: Strings,
    dark: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    units: UnitSystem = UnitSystem.Metric,
) {
    val display = relationAnalysisDisplay(summary, strings, units = units)
    Column(
        modifier =
            modifier
                .padding(RELATION_PANEL_PADDING_DP.dp)
                .widthIn(max = RELATION_PANEL_MAX_WIDTH_DP.dp)
                .background(ROUTE_PANEL_BACKGROUND, RoundedCornerShape(RELATION_PANEL_CORNER_DP.dp))
                .padding(RELATION_PANEL_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(RELATION_PANEL_ROW_GAP_DP.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = display.title,
                modifier = Modifier.weight(1f),
                style =
                    TextStyle(
                        color = ROUTE_VALUE_COLOR,
                        fontSize = RELATION_TITLE_FONT_SP.sp,
                        fontWeight = FontWeight.Bold,
                    ),
            )
            BasicText(
                text = RELATION_CLOSE_GLYPH,
                style = TextStyle(color = ROUTE_VALUE_COLOR, fontSize = RELATION_CONTROL_FONT_SP.sp),
                modifier =
                    Modifier
                        .semantics { contentDescription = strings.resolve(RELATION_CLOSE_KEY) }
                        .background(ROUTE_CHIP_COLOR, CircleShape)
                        .clickable(onClick = onClose)
                        .padding(RELATION_CLOSE_PADDING_DP.dp),
            )
        }
        BasicText(
            text = display.subtitle,
            style = TextStyle(color = ROUTE_STATUS_COLOR, fontSize = RELATION_SUBTITLE_FONT_SP.sp),
        )
        display.reference?.let { reference ->
            BasicText(
                text = reference,
                style = TextStyle(color = ROUTE_STATUS_COLOR, fontSize = RELATION_REFERENCE_FONT_SP.sp),
            )
        }
        BasicText(
            text = display.stats,
            style =
                TextStyle(
                    color = ROUTE_VALUE_COLOR,
                    fontSize = RELATION_STATS_FONT_SP.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
        )
        if (chart.isDrawable) {
            ElevationChart(
                state = chart,
                strings = strings,
                dark = dark,
                onAxis = { _: ElevationChartAxis -> },
                units = units,
            )
        }
    }
}

private const val RELATION_PANEL_CORNER_DP = 12
private const val RELATION_PANEL_PADDING_DP = 12
private const val RELATION_PANEL_MAX_WIDTH_DP = 520
private const val RELATION_PANEL_ROW_GAP_DP = 8
private const val RELATION_TITLE_FONT_SP = 17
private const val RELATION_SUBTITLE_FONT_SP = 13
private const val RELATION_REFERENCE_FONT_SP = 12
private const val RELATION_STATS_FONT_SP = 15
private const val RELATION_CONTROL_FONT_SP = 18
private const val RELATION_CLOSE_PADDING_DP = 6
private const val RELATION_CLOSE_GLYPH = "\u00D7"
private const val RELATION_STATS_SEPARATOR = " · "
private const val RELATION_UNTITLED_KEY = "relation_analysis_untitled"
private const val RELATION_REF_KEY = "relation_analysis_ref"
private const val RELATION_CLOSE_KEY = "relation_analysis_close"
private const val RELATION_ASCENT_KEY = "elevation_chart_range_ascent"
private const val RELATION_ASCENT_FEET_KEY = "elevation_chart_range_ascent_feet"
private const val RELATION_DESCENT_KEY = "elevation_chart_range_descent"
private const val RELATION_DESCENT_FEET_KEY = "elevation_chart_range_descent_feet"
private const val RELATION_HIKING_INTERNATIONAL_KEY = "relation_analysis_hiking_international"
private const val RELATION_HIKING_NATIONAL_KEY = "relation_analysis_hiking_national"
private const val RELATION_HIKING_REGIONAL_KEY = "relation_analysis_hiking_regional"
private const val RELATION_HIKING_LOCAL_KEY = "relation_analysis_hiking_local"
private const val RELATION_CYCLING_INTERNATIONAL_KEY = "relation_analysis_cycling_international"
private const val RELATION_CYCLING_NATIONAL_KEY = "relation_analysis_cycling_national"
private const val RELATION_CYCLING_REGIONAL_KEY = "relation_analysis_cycling_regional"
private const val RELATION_CYCLING_LOCAL_KEY = "relation_analysis_cycling_local"
