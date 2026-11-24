package nl.r1a.grassmaps.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmaps.plugin.ui.api.LayerRowSpec
import nl.r1a.grassmaps.plugin.ui.api.LayerStyleRow
import nl.r1a.grassmaps.plugin.ui.api.LocalShellColors
import nl.r1a.grassmaps.plugin.ui.api.StyleSwatch
import nl.r1a.grassmaps.plugin.ui.api.inkOn

private const val SECTION_CORNER_DP = 8
private const val SECTION_PADDING_DP = 12
private const val ROW_GAP_DP = 6
private const val LABEL_FONT_SP = 13
private const val TITLE_FONT_SP = 18
private const val CONTROL_STROKE_DP = 2
private const val SWITCH_WIDTH_DP = 38
private const val SWITCH_HEIGHT_DP = 22
private const val SWITCH_THUMB_DP = 16
private const val SWATCH_SIZE_DP = 20
private const val SWATCH_CORNER_DP = 5
private const val RADIO_SIZE_DP = 20
private const val RADIO_DOT_DP = 10

/**
 * The Layers card's sheet anchor and chrome.
 *
 * The card used to be an anchored popup at the Layers button. It is a sheet at
 * the foot of the screen now, with the same handle the other shell sheets use.
 */
val LAYERS_SHEET_ANCHOR: Alignment = Alignment.BottomCenter
const val LAYERS_SHEET_HAS_HANDLE = true

/**
 * The full-screen destination behind the Layers card's "More..." row.
 *
 * The map's all-profiles grid button already opens `ProfilesScreenSession`,
 * so the new row reuses that entry point and no screen is added. The theme
 * settings screen has a session too, but only its debug command opens it.
 */
const val LAYERS_MORE_ENTRY_POINT = "all_profiles"

/**
 * The Layers card as a bottom sheet.
 *
 * The host supplies the plugin rows; this composable supplies only the sheet
 * chrome. The rows keep their own state and persistence.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun LayersSheet(
    paneTitle: String,
    closeLabel: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ShellBottomSheet(
        sheetTitle = paneTitle,
        onDismiss = onDismiss,
        anchor = LAYERS_SHEET_ANCHOR,
        showHandle = LAYERS_SHEET_HAS_HANDLE,
        header = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    text = paneTitle,
                    style = TextStyle(color = LocalShellColors.current.ink, fontSize = TITLE_FONT_SP.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
                SheetCloseButton(label = closeLabel, onClick = onDismiss)
            }
        },
        content = content,
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
fun LayerSection(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalShellColors.current
    val shape = RoundedCornerShape(SECTION_CORNER_DP.dp)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .border(1.dp, colors.ring, shape)
                .padding(horizontal = SECTION_PADDING_DP.dp, vertical = ROW_GAP_DP.dp),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
    ) {
        if (title != null) LayerLabel(title, colors.muted)
        content()
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
fun LayerToggle(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val colors = LocalShellColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = LayerRowSpec.ROW_MIN_HEIGHT_DP.dp)
                .clip(RoundedCornerShape(SECTION_CORNER_DP.dp))
                .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
                .padding(ROW_GAP_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LayerLabel(label, colors.ink, Modifier.weight(1f))
        LayerSwitch(checked)
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun LayerSwitch(checked: Boolean) {
    val colors = LocalShellColors.current
    Box(
        modifier =
            Modifier
                .size(SWITCH_WIDTH_DP.dp, SWITCH_HEIGHT_DP.dp)
                .background(if (checked) colors.accent else colors.soft, CircleShape)
                .border(CONTROL_STROKE_DP.dp, if (checked) colors.accent else colors.controlBorder, CircleShape),
    ) {
        Box(
            Modifier
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = CONTROL_STROKE_DP.dp)
                .size(SWITCH_THUMB_DP.dp)
                .background(if (checked) colors.onAccent else colors.offSwitchKnob, CircleShape),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Suppress("ktlint:standard:function-naming")
@Composable
fun LayerMarkerShapeOptions(
    title: String,
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = LocalShellColors.current
    val target = LayerRowSpec.RADIO_MIN_SIZE_DP.dp
    LayerLabel(title, colors.muted)
    FlowRow(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val fill = if (selected) colors.selectedChip else colors.soft
            Box(
                modifier =
                    Modifier
                        .sizeIn(minWidth = target, minHeight = target)
                        .clip(CircleShape)
                        .background(fill)
                        .selectable(selected = selected, role = Role.RadioButton) { onSelect(index) }
                        .padding(ROW_GAP_DP.dp),
                contentAlignment = Alignment.Center,
            ) {
                LayerLabel(label, if (selected) inkOn(fill, colors) else colors.ink)
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
fun LayerStyleChoices(
    rows: List<LayerStyleRow>,
    moreLabel: String,
    onSelect: (Int) -> Unit,
    onMore: () -> Unit,
) {
    val colors = LocalShellColors.current
    Column(Modifier.selectableGroup()) {
        rows.forEachIndexed { index, row -> LayerStyleChoice(row) { onSelect(index) } }
    }
    val shape = RoundedCornerShape(SECTION_CORNER_DP.dp)
    LayerLabel(
        text = moreLabel,
        color = colors.ink,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = LayerRowSpec.ROW_MIN_HEIGHT_DP.dp)
                .clip(shape)
                .border(1.dp, colors.controlBorder, shape)
                .clickable(role = Role.Button, onClick = onMore)
                .padding(SECTION_PADDING_DP.dp),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun LayerStyleChoice(
    row: LayerStyleRow,
    onSelect: () -> Unit,
) {
    val colors = LocalShellColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = LayerRowSpec.ROW_MIN_HEIGHT_DP.dp)
                .clip(RoundedCornerShape(SECTION_CORNER_DP.dp))
                .selectable(selected = row.selected, role = Role.RadioButton, onClick = onSelect)
                .padding(ROW_GAP_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LayerStyleRadio(row.selected)
        val swatch = if (row.swatch == StyleSwatch.MEADOW) colors.accent else colors.amber
        Box(Modifier.size(SWATCH_SIZE_DP.dp).background(swatch, RoundedCornerShape(SWATCH_CORNER_DP.dp)))
        Column {
            LayerLabel(row.name, colors.ink)
            LayerLabel(row.hint, colors.muted)
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun LayerStyleRadio(selected: Boolean) {
    val colors = LocalShellColors.current
    val color = if (selected) colors.accent else colors.controlBorder
    Box(
        modifier = Modifier.size(RADIO_SIZE_DP.dp).border(CONTROL_STROKE_DP.dp, color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(Modifier.size(RADIO_DOT_DP.dp).background(color, CircleShape))
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun LayerLabel(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    BasicText(text = text, style = TextStyle(color = color, fontSize = LABEL_FONT_SP.sp), modifier = modifier)
}
