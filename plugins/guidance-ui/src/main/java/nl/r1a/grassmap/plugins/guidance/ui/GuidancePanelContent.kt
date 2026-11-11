package nl.r1a.grassmap.plugins.guidance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmap.plugin.api.service.Strings

private const val PANEL_CORNER_DP = 12
private const val PANEL_PADDING_DP = 12
private const val PANEL_MAX_WIDTH_DP = 420
private const val ROW_GAP_DP = 8
private const val ROW_INNER_GAP_DP = 6
private const val TITLE_FONT_SP = 16
private const val DETAIL_FONT_SP = 14
private const val NOTICE_FONT_SP = 13
private val PANEL_BACKGROUND = Color(0xF2202020)
private val TITLE_COLOR = Color.White
private val DETAIL_COLOR = Color(0xFFBDBDBD)
private val NOTICE_COLOR = Color(0xFFFFB74D)

/**
 * The guidance chrome over the map (T-0310).
 *
 * It draws the [GuidancePanel] it is given and reads no camera, location or
 * navigation flow. The caller derives the panel once per published navigation
 * state and remembers it, so a camera or gesture frame cannot recompute
 * guidance and this composable is not on the map's draw path.
 *
 * A null panel draws nothing: there is no route to guide.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun GuidancePanelContent(
    panel: GuidancePanel?,
    strings: Strings,
    modifier: Modifier = Modifier,
) {
    if (panel == null) {
        return
    }
    Column(
        modifier =
            modifier
                .padding(PANEL_PADDING_DP.dp)
                .widthIn(max = PANEL_MAX_WIDTH_DP.dp)
                .background(PANEL_BACKGROUND, RoundedCornerShape(PANEL_CORNER_DP.dp))
                .padding(PANEL_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
    ) {
        if (panel.notice != null) {
            BasicText(
                text = strings.resolve(GuidanceStrings.OFF_ROUTE),
                style = TextStyle(color = NOTICE_COLOR, fontSize = NOTICE_FONT_SP.sp, fontWeight = FontWeight.Medium),
            )
        }
        for (entry in panel.entries) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ROW_INNER_GAP_DP.dp),
            ) {
                BasicText(
                    text = entry.title,
                    modifier = Modifier.weight(1f),
                    style = TextStyle(color = TITLE_COLOR, fontSize = TITLE_FONT_SP.sp, fontWeight = FontWeight.SemiBold),
                )
                entry.detail?.let { detail ->
                    BasicText(
                        text = detail,
                        style = TextStyle(color = DETAIL_COLOR, fontSize = DETAIL_FONT_SP.sp),
                    )
                }
            }
        }
    }
}
