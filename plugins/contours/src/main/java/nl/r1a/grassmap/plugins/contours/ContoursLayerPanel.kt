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
 * The Layers button and the style switch live in `plugins/layers-panel`
 * (T-0109). This card is one layer row below them. It persists through the
 * host's `ContoursSettings`. The panel is chrome: the plugin supplies the row
 * and the app supplies the state and the callback. A plugin the user turned off
 * in the Plugins screen has no row here: the switch would keep a setting no
 * layer reads, so the card is drawn only while [pluginEnabled] is true.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun ContoursLayerPanel(
    pluginEnabled: Boolean,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val toggle = contoursPanelToggle(pluginEnabled, enabled) ?: return
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
                        value = toggle,
                        role = Role.Switch,
                        onValueChange = onEnabledChange,
                    ).padding(vertical = PANEL_GAP_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp),
        ) {
            PanelText(stringResource(R.string.map_contours_toggle), PANEL_FOREGROUND)
            PanelText(
                stringResource(
                    if (toggle) R.string.map_contours_on else R.string.map_contours_off,
                ),
                PANEL_MUTED,
            )
        }
    }
}

/**
 * The row's own switch state, or null when the card must not be drawn because
 * the plugin behind it is off.
 */
fun contoursPanelToggle(
    pluginEnabled: Boolean,
    enabled: Boolean,
): Boolean? = if (pluginEnabled) enabled else null

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
