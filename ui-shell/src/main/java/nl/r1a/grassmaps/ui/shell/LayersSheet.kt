package nl.r1a.grassmaps.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmaps.plugin.ui.api.LayerRowSpec
import nl.r1a.grassmaps.plugin.ui.api.LayerStyleRow
import nl.r1a.grassmaps.plugin.ui.api.LocalShellColors
import nl.r1a.grassmaps.plugin.ui.api.ShellColors
import nl.r1a.grassmaps.plugin.ui.api.StyleSwatch
import nl.r1a.grassmaps.plugin.ui.api.chipOutline
import nl.r1a.grassmaps.plugin.ui.api.choiceOutline
import nl.r1a.grassmaps.plugin.ui.api.dashedOutline
import nl.r1a.grassmaps.plugin.ui.api.inkOn
import nl.r1a.grassmaps.plugin.ui.api.switchOutline

private const val SECTION_CORNER_DP = 8
private const val SECTION_PADDING_DP = 12
private const val ROW_GAP_DP = 6
private const val LABEL_FONT_SP = 13
private const val TITLE_FONT_SP = 18
private const val CONTROL_STROKE_DP = 2
private const val SWITCH_WIDTH_DP = 38
private const val SWITCH_HEIGHT_DP = 22
private const val SWITCH_THUMB_DP = 16
private const val RADIO_SIZE_DP = 20
private const val RADIO_DOT_DP = 10
private const val STYLE_TILE_MIN_WIDTH_DP = 76
private const val THUMB_HEIGHT_DP = 64
private const val TILE_CORNER_DP = 12
private const val BADGE_INSET_DP = 4
private const val MORE_PLUS_FONT_SP = 24
private const val PARK_WIDTH_FRACTION = 0.58f
private const val PARK_TOP_FRACTION = 0.12f
private const val PARK_HEIGHT_FRACTION = 0.88f
private const val ROAD_LEFT_FRACTION = 0.74f
private const val ROAD_WIDTH_DP = 9
private const val TRAIL_STROKE_DP = 2
private const val TRAIL_DASH_ON_DP = 5
private const val TRAIL_DASH_OFF_DP = 4
private const val TRAIL_START_X_FRACTION = 0.1f
private const val TRAIL_START_Y_FRACTION = 0.7f
private const val TRAIL_END_X_FRACTION = 0.5f
private const val TRAIL_END_Y_FRACTION = 0.18f

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
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
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
                .switchOutline(colors, checked, CircleShape),
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
                        .chipOutline(colors, CircleShape, selection = if (selected) colors.selectedChip else null)
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
    Row(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
        verticalAlignment = Alignment.Top,
    ) {
        rows.forEachIndexed { index, row ->
            LayerStyleThumbnail(row = row) { onSelect(index) }
        }
        MoreStyleTile(label = moreLabel, onClick = onMore)
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun RowScope.LayerStyleThumbnail(
    row: LayerStyleRow,
    onSelect: () -> Unit,
) {
    val colors = LocalShellColors.current
    Box(
        modifier =
            Modifier
                .weight(1f)
                .sizeIn(minWidth = STYLE_TILE_MIN_WIDTH_DP.dp, minHeight = LayerRowSpec.ROW_MIN_HEIGHT_DP.dp)
                .selectable(selected = row.selected, role = Role.RadioButton, onClick = onSelect),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(THUMB_HEIGHT_DP.dp)
                        .clip(RoundedCornerShape(TILE_CORNER_DP.dp)),
            ) {
                StylePreview(row, colors)
                Box(
                    Modifier
                        .fillMaxSize()
                        .choiceOutline(
                            colors,
                            RoundedCornerShape(TILE_CORNER_DP.dp),
                            selection = if (row.selected) colors.accent else null,
                        ),
                )
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(BADGE_INSET_DP.dp)
                        .background(colors.surface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    LayerStyleRadio(row.selected)
                }
            }
            LayerLabel(row.name, colors.ink)
            LayerLabel(row.hint, colors.muted)
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun StylePreview(
    row: LayerStyleRow,
    colors: ShellColors,
) {
    // The app ships no preview artwork. The style's own token fills the park,
    // and shell tokens draw the road and trail, so the preview follows the theme.
    val park = if (row.swatch == StyleSwatch.MEADOW) colors.accent else colors.amber
    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(THUMB_HEIGHT_DP.dp)
                .clip(RoundedCornerShape(TILE_CORNER_DP.dp)),
    ) {
        drawRect(colors.soft)
        drawRect(
            color = park,
            topLeft = Offset(0f, size.height * PARK_TOP_FRACTION),
            size = Size(size.width * PARK_WIDTH_FRACTION, size.height * PARK_HEIGHT_FRACTION),
        )
        drawRect(
            color = colors.offSwitchKnob,
            topLeft = Offset(size.width * ROAD_LEFT_FRACTION, 0f),
            size = Size(ROAD_WIDTH_DP.dp.toPx(), size.height),
        )
        drawLine(
            color = colors.onAccent,
            start = Offset(size.width * TRAIL_START_X_FRACTION, size.height * TRAIL_START_Y_FRACTION),
            end = Offset(size.width * TRAIL_END_X_FRACTION, size.height * TRAIL_END_Y_FRACTION),
            strokeWidth = TRAIL_STROKE_DP.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(TRAIL_DASH_ON_DP.dp.toPx(), TRAIL_DASH_OFF_DP.dp.toPx())),
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun RowScope.MoreStyleTile(
    label: String,
    onClick: () -> Unit,
) {
    val colors = LocalShellColors.current
    Box(
        modifier =
            Modifier
                .weight(1f)
                .sizeIn(minWidth = STYLE_TILE_MIN_WIDTH_DP.dp, minHeight = LayerRowSpec.ROW_MIN_HEIGHT_DP.dp)
                .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(THUMB_HEIGHT_DP.dp)
                        .clip(RoundedCornerShape(TILE_CORNER_DP.dp))
                        .background(colors.surface)
                        .dashedOutline(colors, TILE_CORNER_DP.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = "+",
                    style = TextStyle(color = colors.ink, fontSize = MORE_PLUS_FONT_SP.sp),
                )
            }
            LayerLabel(label, colors.ink)
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun LayerStyleRadio(selected: Boolean) {
    val colors = LocalShellColors.current
    Box(
        modifier = Modifier.size(RADIO_SIZE_DP.dp).switchOutline(colors, selected, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(Modifier.size(RADIO_DOT_DP.dp).background(colors.accent, CircleShape))
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
