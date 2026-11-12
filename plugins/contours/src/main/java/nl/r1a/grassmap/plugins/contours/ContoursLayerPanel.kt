package nl.r1a.grassmap.plugins.contours

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PANEL_BACKGROUND = Color(0xCC000000)
private val PANEL_FOREGROUND = Color.White
private val PANEL_MUTED = Color(0xCCFFFFFF)
private const val PANEL_CORNER_DP = 8
private const val PANEL_HORIZONTAL_DP = 12
private const val PANEL_VERTICAL_DP = 8
private const val PANEL_GAP_DP = 6
private const val PANEL_FONT_SP = 13

/**
 * The minimal layers panel for the contour overlay.
 *
 * T-0109 owns the full layers panel and the style switch. Until that lands, the
 * contour row is the one layer a user can turn off, and it persists through the
 * host's `ContoursSettings`. The panel is chrome: the plugin supplies the row
 * and the app supplies the state and the callback.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun ContoursLayerPanel(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(PANEL_CORNER_DP.dp))
                .background(PANEL_BACKGROUND)
                .padding(horizontal = PANEL_HORIZONTAL_DP.dp, vertical = PANEL_VERTICAL_DP.dp),
        verticalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp),
    ) {
        PanelText(stringResource(R.string.map_contours_panel_title), PANEL_MUTED)
        Row(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(PANEL_CORNER_DP.dp))
                    .toggleable(
                        value = enabled,
                        role = Role.Switch,
                        onValueChange = onEnabledChange,
                    ).padding(vertical = PANEL_GAP_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp),
        ) {
            PanelText(stringResource(R.string.map_contours_toggle), PANEL_FOREGROUND)
            PanelText(
                stringResource(
                    if (enabled) R.string.map_contours_on else R.string.map_contours_off,
                ),
                PANEL_MUTED,
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun PanelText(
    text: String,
    color: Color,
) {
    BasicText(
        text = text,
        style = TextStyle(color = color, fontSize = PANEL_FONT_SP.sp),
    )
}
