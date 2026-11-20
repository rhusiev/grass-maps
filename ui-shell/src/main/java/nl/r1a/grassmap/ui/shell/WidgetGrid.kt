package nl.r1a.grassmap.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmap.plugin.ui.api.LocalShellColors
import nl.r1a.grassmap.plugin.ui.api.ShellColors

/** One line of a widget: the value and the short label that names it, for example "296 m" and "alt". */
data class WidgetLine(
    val value: String,
    val label: String = "",
)

/**
 * One widget as the grid draws it. The grid only draws text: the app turns a
 * stream value into [lines] and [title], so the shell never reads a widget
 * contract. A cell with [clearDescription] draws a clear action after its text.
 */
data class WidgetCell(
    val widget: GridWidget,
    val title: String,
    val lines: List<WidgetLine>,
    val clearDescription: String? = null,
)

/** The chrome text of the grid in edit mode. The widget [title] is the argument of each description. */
data class WidgetGridLabels(
    val addDescription: String,
    val remove: (title: String) -> String,
    val moveEarlier: (title: String) -> String,
    val moveLater: (title: String) -> String,
)

/**
 * The one spoken line of a cell: its title and each value. The short labels
 * under the values are visual unit hints; the values already carry their unit,
 * so repeating the labels would say the same thing twice.
 */
fun widgetCellDescription(cell: WidgetCell): String {
    val values = cell.lines.map { it.value }.filter { it.isNotBlank() }
    return when {
        cell.title.isBlank() -> values.joinToString(", ")
        values.isEmpty() -> cell.title
        else -> "${cell.title}: ${values.joinToString(", ")}"
    }
}

/**
 * Whether a value change is announced. Only the selected point changes on a
 * user action; altitude, GPS accuracy, coordinates and speeds move with every
 * fix, and a live region on them would chatter.
 */
fun widgetAnnouncesValueChanges(widget: GridWidget): Boolean = widget == GridWidget.SelectedPoint

/**
 * The read-only info widgets at the top of the map. Outside edit mode it draws
 * the cells and nothing else. In edit mode every cell gets move-earlier,
 * move-later and remove actions, and the empty slot opens the shared layout
 * sheet. The widgets never move by themselves: a press changes the layout
 * through the callbacks and the host stores it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("ktlint:standard:function-naming")
@Composable
fun WidgetGrid(
    cells: List<WidgetCell>,
    isEditing: Boolean,
    labels: WidgetGridLabels,
    onRemove: (GridWidget) -> Unit,
    onMove: (GridWidget, Int) -> Unit,
    onClear: (GridWidget) -> Unit,
    onOpenPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (cells.isEmpty() && !isEditing) {
        return
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(GRID_GAP_DP.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(GRID_GAP_DP.dp),
            verticalArrangement = Arrangement.spacedBy(GRID_GAP_DP.dp),
        ) {
            cells.forEachIndexed { index, cell ->
                WidgetCellView(
                    cell = cell,
                    isEditing = isEditing,
                    labels = labels,
                    canMoveEarlier = index > 0,
                    canMoveLater = index < cells.lastIndex,
                    onRemove = { onRemove(cell.widget) },
                    onMove = { delta -> onMove(cell.widget, delta) },
                    onClear = { onClear(cell.widget) },
                )
            }
            if (isEditing) {
                EmptyWidgetSlot(labels.addDescription, onOpenPicker)
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun EmptyWidgetSlot(
    description: String,
    onOpenPicker: () -> Unit,
) {
    val colors = LocalShellColors.current
    Box(
        modifier =
            Modifier
                .height(EMPTY_SLOT_HEIGHT_DP.dp)
                .padding(horizontal = GLYPH_PADDING_DP.dp)
                .dashedRing(colors.faint, GRID_CORNER_DP.dp)
                .clickable(role = Role.Button, onClick = onOpenPicker)
                .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = ADD_GLYPH,
            style = TextStyle(color = colors.muted, fontSize = GLYPH_FONT_SP.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun WidgetCellView(
    cell: WidgetCell,
    isEditing: Boolean,
    labels: WidgetGridLabels,
    canMoveEarlier: Boolean,
    canMoveLater: Boolean,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
    onClear: () -> Unit,
) {
    val description = widgetCellDescription(cell)
    val moveActions =
        buildList {
            if (isEditing && canMoveEarlier) {
                add(
                    CustomAccessibilityAction(labels.moveEarlier(cell.title)) {
                        onMove(-1)
                        true
                    },
                )
            }
            if (isEditing && canMoveLater) {
                add(
                    CustomAccessibilityAction(labels.moveLater(cell.title)) {
                        onMove(1)
                        true
                    },
                )
            }
        }
    Row(
        modifier =
            Modifier
                .glass(LocalShellColors.current)
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    if (moveActions.isNotEmpty()) {
                        customActions = moveActions
                    }
                    if (!isEditing && widgetAnnouncesValueChanges(cell.widget)) {
                        liveRegion = LiveRegionMode.Polite
                    }
                }.padding(horizontal = CELL_PADDING_DP.dp, vertical = CELL_VERTICAL_PADDING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GRID_GAP_DP.dp),
    ) {
        if (isEditing) {
            GridGlyph(MOVE_EARLIER_GLYPH, labels.moveEarlier(cell.title), canMoveEarlier) { onMove(-1) }
        }
        Column {
            cell.lines.forEach { line -> WidgetLineView(line) }
        }
        if (isEditing) {
            GridGlyph(MOVE_LATER_GLYPH, labels.moveLater(cell.title), canMoveLater) { onMove(1) }
            GridGlyph(REMOVE_GLYPH, labels.remove(cell.title), true, onRemove)
        } else if (cell.clearDescription != null) {
            GridGlyph(CLEAR_GLYPH, cell.clearDescription, true, onClear)
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun WidgetLineView(line: WidgetLine) {
    Row(horizontalArrangement = Arrangement.spacedBy(LABEL_GAP_DP.dp), verticalAlignment = Alignment.Bottom) {
        val colors = LocalShellColors.current
        BasicText(line.value, style = TextStyle(color = colors.ink, fontSize = VALUE_FONT_SP.sp, fontWeight = FontWeight.SemiBold))
        if (line.label.isNotEmpty()) {
            BasicText(line.label, style = TextStyle(color = colors.muted, fontSize = LABEL_FONT_SP.sp))
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun GridGlyph(
    glyph: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    BasicText(
        text = glyph,
        style =
            TextStyle(
                color = if (enabled) LocalShellColors.current.ink else LocalShellColors.current.faint,
                fontSize = GLYPH_FONT_SP.sp,
            ),
        modifier =
            Modifier
                .clip(RoundedCornerShape(GRID_CORNER_DP.dp))
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description }
                .padding(horizontal = GLYPH_PADDING_DP.dp),
    )
}

private fun Modifier.glass(colors: ShellColors): Modifier =
    this
        .background(colors.glass, RoundedCornerShape(GRID_CORNER_DP.dp))
        .border(1.dp, colors.ring, RoundedCornerShape(GRID_CORNER_DP.dp))

private const val MOVE_EARLIER_GLYPH = "‹"
private const val MOVE_LATER_GLYPH = "›"
private const val REMOVE_GLYPH = "−"
private const val CLEAR_GLYPH = "×"
private const val ADD_GLYPH = "+"
private const val GRID_GAP_DP = 6
private const val GRID_CORNER_DP = 10
private const val EMPTY_SLOT_HEIGHT_DP = 38
private const val CELL_PADDING_DP = 10
private const val CELL_VERTICAL_PADDING_DP = 6
private const val GLYPH_PADDING_DP = 6
private const val VALUE_FONT_SP = 14
private const val LABEL_FONT_SP = 12
private const val GLYPH_FONT_SP = 18
private const val LABEL_GAP_DP = 4
