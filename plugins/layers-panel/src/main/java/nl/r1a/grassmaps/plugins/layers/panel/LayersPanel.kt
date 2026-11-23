package nl.r1a.grassmaps.plugins.layers.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmaps.plugin.ui.api.LayerRowSpec
import nl.r1a.grassmaps.plugin.ui.api.LayerStyleOption
import nl.r1a.grassmaps.plugin.ui.api.LayerStyleRow
import nl.r1a.grassmaps.plugin.ui.api.LocalShellColors
import nl.r1a.grassmaps.plugin.ui.api.ShellColors
import nl.r1a.grassmaps.plugin.ui.api.StyleSwatch
import nl.r1a.grassmaps.plugin.ui.api.layerStyleRows

private const val BUTTON_CORNER_DP = 8
private const val BUTTON_HORIZONTAL_DP = 12
private const val BUTTON_VERTICAL_DP = 6
private const val BUTTON_FONT_SP = 13
private const val PANEL_CORNER_DP = 8
private const val PANEL_HORIZONTAL_DP = 12
private const val PANEL_VERTICAL_DP = 8
private const val PANEL_GAP_DP = 6
private const val PANEL_FONT_SP = 13
private const val STYLE_HINT_GAP_DP = 2
private const val SWATCH_SIZE_DP = 20
private const val SWATCH_CORNER_DP = 5
private const val SELECTED_MARK = "●"
private const val UNSELECTED_MARK = "○"
private const val MORE_MARK = "+"

/**
 * The one small button the layers controls hide behind.
 *
 * The cards are closed until this is pressed. A card that is always open
 * covers the map's centre on a phone. The button sits at the bottom end of the
 * map, above the scale bar, and the cards open above it.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun LayersButton(
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalShellColors.current
    val shape = RoundedCornerShape(BUTTON_CORNER_DP.dp)
    BasicText(
        text = stringResource(R.string.layers_panel_button),
        style = TextStyle(color = colors.ink, fontSize = BUTTON_FONT_SP.sp),
        modifier =
            modifier
                .clip(shape)
                .background(colors.glass)
                .border(1.dp, colors.ring, shape)
                .toggleable(
                    value = open,
                    role = Role.Button,
                    onValueChange = { onToggle() },
                ).padding(
                    horizontal = BUTTON_HORIZONTAL_DP.dp,
                    vertical = BUTTON_VERTICAL_DP.dp,
                ),
    )
}

/**
 * The card that switches the map style: Meadow for everyday and city use,
 * Trail for cycling and hiking. The choice is the whole card. The app persists
 * it, so a pick survives a restart.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun MapStylePanel(
    active: MapStyleChoice,
    onSelect: (MapStyleChoice) -> Unit,
    onMore: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalShellColors.current
    val shape = RoundedCornerShape(PANEL_CORNER_DP.dp)
    Column(
        modifier =
            modifier
                .clip(shape)
                .background(colors.surface)
                .border(1.dp, colors.ring, shape)
                .padding(horizontal = PANEL_HORIZONTAL_DP.dp, vertical = PANEL_VERTICAL_DP.dp),
        verticalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp),
    ) {
        PanelText(stringResource(R.string.layers_panel_style_title), colors.muted)
        val choices = MapStyleChoice.entries
        val options =
            choices.map { choice ->
                LayerStyleOption(
                    name = stringResource(choice.nameRes()),
                    hint = stringResource(choice.hintRes()),
                    swatch = choice.swatch(),
                )
            }
        val rows = layerStyleRows(options, selectedName = stringResource(active.nameRes()))
        rows.zip(choices).forEach { (row, choice) ->
            StyleRow(row = row, colors = colors, onSelect = { onSelect(choice) })
        }
        if (onMore != null) {
            MoreRow(label = stringResource(R.string.layers_panel_more), colors = colors, onClick = onMore)
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun StyleRow(
    row: LayerStyleRow,
    colors: ShellColors,
    onSelect: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(PANEL_CORNER_DP.dp))
                .selectable(
                    selected = row.selected,
                    role = Role.RadioButton,
                    onClick = onSelect,
                ).heightIn(min = LayerRowSpec.ROW_MIN_HEIGHT_DP.dp)
                .padding(horizontal = PANEL_GAP_DP.dp, vertical = PANEL_GAP_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PanelText(
            text = if (row.selected) SELECTED_MARK else UNSELECTED_MARK,
            color = colors.ink,
            // The radio role already carries the selected state; the mark is
            // decoration and must not be read as a character.
            modifier = Modifier.clearAndSetSemantics {},
        )
        Box(
            modifier =
                Modifier
                    .size(SWATCH_SIZE_DP.dp)
                    .clip(RoundedCornerShape(SWATCH_CORNER_DP.dp))
                    .background(colors.swatchColor(row.swatch))
                    // The name carries the choice; the swatch is only a preview.
                    .clearAndSetSemantics {},
        )
        Column(verticalArrangement = Arrangement.spacedBy(STYLE_HINT_GAP_DP.dp)) {
            PanelText(row.name, colors.ink)
            PanelText(row.hint, colors.muted)
        }
    }
}

/** A flat swatch from two existing tokens; the app ships no preview artwork. */
private fun ShellColors.swatchColor(swatch: StyleSwatch): Color =
    when (swatch) {
        StyleSwatch.MEADOW -> accent
        StyleSwatch.TRAIL -> amber
    }

/**
 * The dashed "More..." card's stand-in: a quiet outlined row that opens the
 * map's existing all-profiles screen. It keeps the same 48 dp tap height.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun MoreRow(
    label: String,
    colors: ShellColors,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(PANEL_CORNER_DP.dp)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .border(1.dp, colors.controlBorder, shape)
                .clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = LayerRowSpec.ROW_MIN_HEIGHT_DP.dp)
                .padding(horizontal = PANEL_GAP_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PanelText(text = MORE_MARK, color = colors.muted, modifier = Modifier.clearAndSetSemantics {})
        PanelText(text = label, color = colors.ink)
    }
}

private fun MapStyleChoice.nameRes(): Int =
    when (this) {
        MapStyleChoice.MEADOW -> R.string.layers_panel_style_meadow
        MapStyleChoice.TRAIL -> R.string.layers_panel_style_trail
    }

private fun MapStyleChoice.hintRes(): Int =
    when (this) {
        MapStyleChoice.MEADOW -> R.string.layers_panel_style_meadow_hint
        MapStyleChoice.TRAIL -> R.string.layers_panel_style_trail_hint
    }

@Suppress("ktlint:standard:function-naming")
@Composable
private fun PanelText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    BasicText(text = text, style = TextStyle(color = color, fontSize = PANEL_FONT_SP.sp), modifier = modifier)
}
