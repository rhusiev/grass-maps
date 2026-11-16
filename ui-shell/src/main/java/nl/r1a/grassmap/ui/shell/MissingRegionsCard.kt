package nl.r1a.grassmap.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmap.plugin.api.model.CatalogPartKind
import nl.r1a.grassmap.plugin.api.model.MissingRegion
import nl.r1a.grassmap.plugin.api.model.MissingRegions
import nl.r1a.grassmap.plugin.api.model.MissingRegionsDownload
import nl.r1a.grassmap.plugin.api.service.Strings
import java.util.Locale

private const val CARD_CORNER_DP = 10
private const val CARD_PADDING_DP = 10
private const val CARD_GAP_DP = 6
private const val BUTTON_CORNER_DP = 8
private const val BUTTON_PADDING_HORIZONTAL_DP = 12
private const val BUTTON_PADDING_VERTICAL_DP = 8
private const val TITLE_FONT_SP = 15
private const val BODY_FONT_SP = 13
private const val BYTES_PER_KB = 1024.0
private const val BYTES_PER_MB = BYTES_PER_KB * 1024.0
private const val BYTES_PER_GB = BYTES_PER_MB * 1024.0
private const val SMALL_SIZE_LIMIT = 10.0
private const val PERCENT = 100L

private const val MISSING_TITLE_KEY = "route_planner_missing_title"
private const val MISSING_TITLE_MANY_KEY = "route_planner_missing_title_many"
private const val MISSING_SUMMARY_KEY = "route_planner_missing_summary"
private const val MISSING_DETAILS_KEY = "route_planner_missing_details"
private const val MISSING_HIDE_DETAILS_KEY = "route_planner_missing_hide_details"
private const val MISSING_DOWNLOAD_KEY = "route_planner_missing_download"
private const val MISSING_PROGRESS_KEY = "route_planner_missing_progress"
private const val MISSING_PROBLEM_KEY = "route_planner_missing_problem"
private const val MISSING_PART_LINE_KEY = "route_planner_missing_part_line"
private const val MISSING_FALLBACK_REGION_KEY = "route_planner_missing_region_fallback"

private val PART_KEYS =
    mapOf(
        CatalogPartKind.MAP to "route_planner_missing_part_map",
        CatalogPartKind.SEARCH to "route_planner_missing_part_search",
        CatalogPartKind.ELEVATION to "route_planner_missing_part_elevation",
        CatalogPartKind.ROUTING to "route_planner_missing_part_routing",
        CatalogPartKind.CONTOURS to "route_planner_missing_part_contours",
    )

/**
 * The prompt a route shows when a stop lies in an oblast that is not on the
 * phone (T-0511): the oblast, what is missing with sizes, and one button that
 * downloads it all and then routes. The card keeps the fixed dark panel
 * colours, so it has no light and dark variants.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun MissingRegionsCard(
    missing: MissingRegions,
    download: MissingRegionsDownload?,
    strings: Strings,
    preferUkrainian: Boolean,
    onDownload: () -> Unit,
) {
    var detailsVisible by remember { mutableStateOf(false) }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(ROUTE_WARNING_BACKGROUND, RoundedCornerShape(CARD_CORNER_DP.dp))
                .padding(CARD_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(CARD_GAP_DP.dp),
    ) {
        val title = missingTitle(missing, strings, preferUkrainian)
        CardText(title, ROUTE_WARNING_TITLE_COLOR, TITLE_FONT_SP, FontWeight.Bold)
        val summary = strings.resolve(MISSING_SUMMARY_KEY, listOf(formatSize(missing.bytes)))
        CardText(summary, ROUTE_WARNING_SUMMARY_COLOR, BODY_FONT_SP)
        if (detailsVisible) {
            missing.regions.forEach { region ->
                RegionParts(region, strings, preferUkrainian, showName = missing.regions.size > 1)
            }
        }
        if (download == null || download.problem != null) {
            if (download?.problem != null) {
                CardText(strings.resolve(MISSING_PROBLEM_KEY), ROUTE_WARNING_TITLE_COLOR, BODY_FONT_SP)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(CARD_GAP_DP.dp)) {
                CardButton(strings.resolve(MISSING_DOWNLOAD_KEY), ROUTE_ACTION_COLOR, onDownload)
                val toggle = strings.resolve(if (detailsVisible) MISSING_HIDE_DETAILS_KEY else MISSING_DETAILS_KEY)
                CardButton(toggle, ROUTE_CHIP_COLOR) { detailsVisible = !detailsVisible }
            }
        } else {
            CardText(progressText(download, strings), ROUTE_WARNING_SUMMARY_COLOR, BODY_FONT_SP)
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun RegionParts(
    region: MissingRegion,
    strings: Strings,
    preferUkrainian: Boolean,
    showName: Boolean,
) {
    if (showName) {
        val name = region.name(preferUkrainian) ?: strings.resolve(MISSING_FALLBACK_REGION_KEY)
        CardText(name, ROUTE_WARNING_TITLE_COLOR, BODY_FONT_SP, FontWeight.Bold)
    }
    region.parts.forEach { part ->
        val partName = PART_KEYS[part.kind]?.let { strings.resolve(it) } ?: part.kind.name
        val line = strings.resolve(MISSING_PART_LINE_KEY, listOf(partName, formatSize(part.bytes)))
        CardText(line, ROUTE_WARNING_SUMMARY_COLOR, BODY_FONT_SP)
    }
}

private fun missingTitle(
    missing: MissingRegions,
    strings: Strings,
    preferUkrainian: Boolean,
): String {
    val first = missing.regions.firstOrNull()
    val name = first?.name(preferUkrainian) ?: strings.resolve(MISSING_FALLBACK_REGION_KEY)
    return if (missing.regions.size > 1) {
        strings.resolve(MISSING_TITLE_MANY_KEY, listOf(name, (missing.regions.size - 1).toString()))
    } else {
        strings.resolve(MISSING_TITLE_KEY, listOf(name))
    }
}

private fun progressText(
    download: MissingRegionsDownload,
    strings: Strings,
): String {
    val percent = if (download.bytesTotal > 0) download.bytesDone * PERCENT / download.bytesTotal else 0L
    return strings.resolve(
        MISSING_PROGRESS_KEY,
        listOf(formatSize(download.bytesDone), formatSize(download.bytesTotal), percent.toString()),
    )
}

/** "152 MB" like the downloads screen: one decimal under 10, none above. */
internal fun formatSize(bytes: Long): String {
    val (value, unit) =
        when {
            bytes >= BYTES_PER_GB -> bytes / BYTES_PER_GB to "GB"
            bytes >= BYTES_PER_MB -> bytes / BYTES_PER_MB to "MB"
            else -> bytes / BYTES_PER_KB to "KB"
        }
    val pattern = if (value < SMALL_SIZE_LIMIT) "%.1f %s" else "%.0f %s"
    return String.format(Locale.US, pattern, value, unit)
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun CardText(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    sizeSp: Int,
    weight: FontWeight = FontWeight.Normal,
) {
    BasicText(
        text = text,
        style = TextStyle(color = color, fontSize = sizeSp.sp, fontWeight = weight),
        modifier = Modifier.semantics { contentDescription = text },
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun CardButton(
    text: String,
    background: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    BasicText(
        text = text,
        style = TextStyle(color = ROUTE_VALUE_COLOR, fontSize = BODY_FONT_SP.sp, fontWeight = FontWeight.Bold),
        modifier =
            Modifier
                .background(background, RoundedCornerShape(BUTTON_CORNER_DP.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = BUTTON_PADDING_HORIZONTAL_DP.dp, vertical = BUTTON_PADDING_VERTICAL_DP.dp),
    )
}
