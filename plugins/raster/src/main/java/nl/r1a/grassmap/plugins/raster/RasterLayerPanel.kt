package nl.r1a.grassmap.plugins.raster

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
private val FIELD_BACKGROUND = Color(0x33FFFFFF)
private const val PANEL_CORNER_DP = 8
private const val PANEL_HORIZONTAL_DP = 12
private const val PANEL_VERTICAL_DP = 8
private const val PANEL_GAP_DP = 6
private const val PANEL_FONT_SP = 13

/**
 * The minimal online-tile panel: the OSM preset toggle, its visible credit,
 * what enabling sends, and a URL template field for a custom source.
 *
 * T-0109 owns the full layers panel; until it lands this row is how the user
 * reaches online tiles and the user-added source. The panel is chrome: the app
 * owns the privacy switch and the source store, and the plugin supplies the
 * row and the copy.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun RasterLayerPanel(
    enabled: Boolean,
    source: RasterSource,
    onEnabledChange: (Boolean) -> Unit,
    onCustomSource: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var template by remember(source) {
        mutableStateOf(if (source.isOpenStreetMap()) "" else source.tileUrlTemplate)
    }
    val attribution =
        if (source.isOpenStreetMap()) {
            stringResource(R.string.map_raster_attribution)
        } else {
            source.attributionHtml
        }
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(PANEL_CORNER_DP.dp))
                .background(PANEL_BACKGROUND)
                .padding(horizontal = PANEL_HORIZONTAL_DP.dp, vertical = PANEL_VERTICAL_DP.dp),
        verticalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp),
    ) {
        PanelText(stringResource(R.string.map_raster_panel_title), PANEL_MUTED)
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
            PanelText(stringResource(R.string.map_raster_toggle), PANEL_FOREGROUND)
            PanelText(
                stringResource(if (enabled) R.string.map_raster_on else R.string.map_raster_off),
                PANEL_MUTED,
            )
        }
        PanelText(stringResource(R.string.map_raster_sends), PANEL_MUTED)
        if (enabled) {
            if (attribution.isNotBlank()) {
                PanelText(attribution, PANEL_MUTED)
            }
            PanelText(stringResource(R.string.map_raster_custom_hint), PANEL_MUTED)
            BasicTextField(
                value = template,
                onValueChange = { template = it },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(PANEL_CORNER_DP.dp))
                        .background(FIELD_BACKGROUND)
                        .padding(horizontal = PANEL_GAP_DP.dp, vertical = PANEL_GAP_DP.dp),
                textStyle = TextStyle(color = PANEL_FOREGROUND, fontSize = PANEL_FONT_SP.sp),
                singleLine = true,
            )
            Row(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(PANEL_CORNER_DP.dp))
                        .clickable { onCustomSource(template.trim()) }
                        .padding(vertical = PANEL_GAP_DP.dp),
                horizontalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp),
            ) {
                PanelText(stringResource(R.string.map_raster_custom_use), PANEL_FOREGROUND)
            }
            PanelText(stringResource(R.string.map_raster_custom_terms), PANEL_MUTED)
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
