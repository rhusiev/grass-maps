package nl.r1a.grassmap.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.ui.api.LocalShellColors

/** One configurable button: the command it runs and its visible text. */
data class ControlButton(
    val id: CommandId,
    val label: String,
    val contentDescription: String,
)

/** The chrome text the shell needs but cannot resolve without app resources. */
data class ControlBarLabels(
    val edit: String,
    val editDone: String,
    val editDescription: String,
    val editDoneDescription: String,
    val add: String,
    val addDescription: String,
    val emptySlotDescription: String,
)

/**
 * The map control bar (T-0606). It only knows command IDs and labels: it
 * invokes through the supplied callbacks, so the app keeps the command bus and
 * the map host.
 *
 * The bar never moves a button while editing. Normal mode draws the placed
 * buttons in slot order; edit mode draws every slot in the same order, a free
 * slot as a dashed box, then Add and Done. The arc arrangement places the
 * placed buttons on [thumbArcOffsets]; the arc's order is the slot order.
 * Invoking is disabled while editing, so an edit cannot fire a command.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun MapControlBar(
    slots: List<ControlButton?>,
    labels: ControlBarLabels,
    isEditing: Boolean,
    arc: Boolean,
    onInvoke: (CommandId) -> Unit,
    onAdd: () -> Unit,
    onEditingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalShellColors.current
    if (arc) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(BUTTON_GAP_DP.dp),
        ) {
            ArcRow(
                buttons = slots.filterNotNull(),
                enabled = !isEditing,
                onInvoke = onInvoke,
            )
            if (isEditing) {
                Row(horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP_DP.dp)) {
                    AddBox(labels, onAdd)
                    EditBox(editing = true, labels, onEditingChange)
                }
            } else {
                EditBox(editing = false, labels, onEditingChange)
            }
        }
        return
    }
    val shape = RoundedCornerShape(BUTTON_CORNER_DP.dp)
    Column(
        modifier =
            modifier
                .background(colors.glass, RoundedCornerShape(BAR_CORNER_DP.dp))
                .border(1.dp, colors.ring, RoundedCornerShape(BAR_CORNER_DP.dp))
                .padding(BUTTON_GAP_DP.dp),
        verticalArrangement = Arrangement.spacedBy(BUTTON_GAP_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isEditing) {
            slots.forEachIndexed { index, button ->
                key(index) {
                    if (button == null) {
                        EmptySlot(labels.emptySlotDescription, onAdd)
                    } else {
                        ActionBox(
                            label = button.label,
                            description = button.contentDescription,
                            enabled = false,
                            onClick = {},
                            shape = shape,
                        )
                    }
                }
            }
            AddBox(labels, onAdd)
        } else {
            slots.filterNotNull().forEach { button ->
                ActionBox(
                    label = button.label,
                    description = button.contentDescription,
                    enabled = true,
                    onClick = { onInvoke(button.id) },
                    shape = shape,
                )
            }
        }
        EditBox(editing = isEditing, labels, onEditingChange)
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ArcRow(
    buttons: List<ControlButton>,
    enabled: Boolean,
    onInvoke: (CommandId) -> Unit,
) {
    if (buttons.isEmpty()) {
        return
    }
    Layout(
        content = {
            buttons.forEach { button ->
                key(button.id.value) {
                    ActionBox(
                        label = button.label,
                        description = button.contentDescription,
                        enabled = enabled,
                        onClick = { onInvoke(button.id) },
                        shape = RoundedCornerShape(BUTTON_CORNER_DP.dp),
                    )
                }
            }
        },
        modifier = Modifier,
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val offsets = thumbArcOffsets(placeables.size)
        val minX = placeables.indices.minOf { index -> offsets[index].x.value - placeables[index].width.toDp().value }
        val minY = placeables.indices.minOf { index -> offsets[index].y.value - placeables[index].height.toDp().value }
        val width = (-minX).dp.roundToPx()
        val height = (-minY).dp.roundToPx()
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                placeable.place(
                    x = width + offsets[index].x.roundToPx() - placeable.width,
                    y = height + offsets[index].y.roundToPx() - placeable.height,
                )
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun EditBox(
    editing: Boolean,
    labels: ControlBarLabels,
    onEditingChange: (Boolean) -> Unit,
) {
    ActionBox(
        label = if (editing) labels.editDone else labels.edit,
        description = if (editing) labels.editDoneDescription else labels.editDescription,
        enabled = true,
        onClick = { onEditingChange(!editing) },
        shape = RoundedCornerShape(BUTTON_CORNER_DP.dp),
        wide = true,
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun AddBox(
    labels: ControlBarLabels,
    onAdd: () -> Unit,
) {
    ActionBox(
        label = labels.add,
        description = labels.addDescription,
        enabled = true,
        onClick = onAdd,
        shape = RoundedCornerShape(BUTTON_CORNER_DP.dp),
        wide = true,
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun EmptySlot(
    description: String,
    onAdd: () -> Unit,
) {
    val colors = LocalShellColors.current
    Box(
        modifier =
            Modifier
                .size(BUTTON_SIZE_DP.dp)
                .dashedRing(colors.faint, BUTTON_CORNER_DP.dp)
                .clickable(role = Role.Button, onClick = onAdd)
                .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = ADD_GLYPH,
            style = TextStyle(color = colors.muted, fontSize = BUTTON_FONT_SP.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/** The separate quick-access button. Editing opens the sheet instead of invoking. */
@Suppress("ktlint:standard:function-naming")
@Composable
fun MapQuickAccessButton(
    button: ControlButton?,
    isEditing: Boolean,
    emptyDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalShellColors.current
    if (button == null && !isEditing) {
        return
    }
    val shape = CircleShape
    val base =
        modifier
            .size(QUICK_ACCESS_SIZE_DP.dp)
            .background(if (button == null) colors.glass else colors.surface, shape)
    Box(
        modifier =
            (
                if (isEditing) {
                    base.dashedRing(colors.faint, QUICK_ACCESS_SIZE_DP.dp / 2)
                } else {
                    base.border(1.dp, colors.ring, shape)
                }
            ).clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = button?.contentDescription ?: emptyDescription },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = button?.label ?: ADD_GLYPH,
            style =
                TextStyle(
                    color = if (button == null) colors.muted else colors.ink,
                    fontSize = QUICK_ACCESS_FONT_SP.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ActionBox(
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    shape: RoundedCornerShape,
    wide: Boolean = false,
) {
    val colors = LocalShellColors.current
    val size =
        if (wide) {
            Modifier.height(BUTTON_SIZE_DP.dp).width(WIDE_BUTTON_WIDTH_DP.dp)
        } else {
            Modifier.size(BUTTON_SIZE_DP.dp)
        }
    Box(
        modifier =
            size
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .background(colors.surface, shape)
                .border(1.dp, colors.controlBorder, shape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style =
                TextStyle(
                    color = colors.ink,
                    fontSize = BUTTON_FONT_SP.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            // The description names the control; the drawn glyph is decoration
            // and would otherwise be read as its own character.
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

private const val BUTTON_SIZE_DP = 44
private const val BUTTON_CORNER_DP = 8
private const val BAR_CORNER_DP = 12
private const val BUTTON_GAP_DP = 4
private const val BUTTON_FONT_SP = 18
private const val QUICK_ACCESS_SIZE_DP = 46
private const val QUICK_ACCESS_FONT_SP = 16
private const val DISABLED_ALPHA = 0.4f
private const val WIDE_BUTTON_WIDTH_DP = 80
private const val ADD_GLYPH = "+"
