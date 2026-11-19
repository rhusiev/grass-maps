package nl.r1a.grassmap.plugins.layers.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BUTTON_BACKGROUND = Color(0xCC000000)
private const val BUTTON_CORNER_DP = 8
private const val BUTTON_HORIZONTAL_DP = 12
private const val BUTTON_VERTICAL_DP = 6
private const val BUTTON_FONT_SP = 13
private val PANEL_BACKGROUND = Color(0xCC000000)
private val PANEL_FOREGROUND = Color.White
private val PANEL_MUTED = Color(0xCCFFFFFF)
private const val PANEL_CORNER_DP = 8
private const val PANEL_HORIZONTAL_DP = 12
private const val PANEL_VERTICAL_DP = 8
private const val PANEL_GAP_DP = 6
private const val PANEL_FONT_SP = 13
private const val SELECTED_MARK = "●"
private const val UNSELECTED_MARK = "○"

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
    BasicText(
        text = stringResource(R.string.layers_panel_button),
        style = TextStyle(color = Color.White, fontSize = BUTTON_FONT_SP.sp),
        modifier =
            modifier
                .clip(RoundedCornerShape(BUTTON_CORNER_DP.dp))
                .background(BUTTON_BACKGROUND)
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
        PanelText(stringResource(R.string.layers_panel_style_title), PANEL_MUTED)
        for (choice in MapStyleChoice.entries) {
            StyleRow(choice = choice, selected = choice == active, onSelect = onSelect)
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun StyleRow(
    choice: MapStyleChoice,
    selected: Boolean,
    onSelect: (MapStyleChoice) -> Unit,
) {
    val name = stringResource(choice.nameRes())
    val hint = stringResource(choice.hintRes())
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(PANEL_CORNER_DP.dp))
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = { onSelect(choice) },
                ).padding(vertical = PANEL_GAP_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp),
    ) {
        PanelText(
            text = if (selected) SELECTED_MARK else UNSELECTED_MARK,
            color = PANEL_FOREGROUND,
            // The radio role already carries the selected state; the mark is
            // decoration and must not be read as a character.
            modifier = Modifier.clearAndSetSemantics {},
        )
        PanelText(name, PANEL_FOREGROUND)
        PanelText(hint, PANEL_MUTED)
    }
}

private fun MapStyleChoice.nameRes(): Int =
    when (this) {
        MapStyleChoice.STANDARD -> R.string.layers_panel_style_meadow
        MapStyleChoice.TOURING -> R.string.layers_panel_style_trail
    }

private fun MapStyleChoice.hintRes(): Int =
    when (this) {
        MapStyleChoice.STANDARD -> R.string.layers_panel_style_meadow_hint
        MapStyleChoice.TOURING -> R.string.layers_panel_style_trail_hint
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
