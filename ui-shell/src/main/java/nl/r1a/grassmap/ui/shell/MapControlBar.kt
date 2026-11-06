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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmap.plugin.api.contract.CommandId

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
    val moveLeft: String,
    val moveLeftDescription: String,
    val moveRight: String,
    val moveRightDescription: String,
)

/**
 * The map control bar. It only knows command IDs and labels: it invokes through
 * the supplied callbacks, so the app keeps the command bus and the map host.
 *
 * In edit mode the bar shows move-left and move-right actions. The last button
 * cannot move right and the first cannot move left. Invoking is disabled while
 * editing, so a move cannot fire a command by accident.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun MapControlBar(
    layout: List<ControlButton>,
    labels: ControlBarLabels,
    isEditing: Boolean,
    onInvoke: (CommandId) -> Unit,
    onMove: (CommandId, Int) -> Unit,
    onEditingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(BUTTON_CORNER_DP.dp)
    Column(
        modifier =
            modifier
                .background(Color(0x99000000), RoundedCornerShape(BAR_CORNER_DP.dp))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(BAR_CORNER_DP.dp))
                .padding(BUTTON_GAP_DP.dp),
        verticalArrangement = Arrangement.spacedBy(BUTTON_GAP_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        layout.forEachIndexed { index, button ->
            Row(horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP_DP.dp)) {
                if (isEditing) {
                    ActionBox(
                        label = labels.moveLeft,
                        description = labels.moveLeftDescription,
                        enabled = index > 0,
                        onClick = { onMove(button.id, -1) },
                        shape = shape,
                    )
                }
                ActionBox(
                    label = button.label,
                    description = button.contentDescription,
                    enabled = !isEditing,
                    onClick = { onInvoke(button.id) },
                    shape = shape,
                )
                if (isEditing) {
                    ActionBox(
                        label = labels.moveRight,
                        description = labels.moveRightDescription,
                        enabled = index < layout.lastIndex,
                        onClick = { onMove(button.id, 1) },
                        shape = shape,
                    )
                }
            }
        }
        ActionBox(
            label = if (isEditing) labels.editDone else labels.edit,
            description = labels.editDescription,
            enabled = true,
            onClick = { onEditingChange(!isEditing) },
            shape = shape,
            wide = true,
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
    val size =
        if (wide) {
            Modifier.height(BUTTON_SIZE_DP.dp).widthIn(min = BUTTON_SIZE_DP.dp)
        } else {
            Modifier.size(BUTTON_SIZE_DP.dp)
        }
    Box(
        modifier =
            size
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .background(Color(0xCC1F1F1F), shape)
                .border(1.dp, Color(0x66FFFFFF), shape)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = if (wide) WIDE_BUTTON_PADDING_DP.dp else 0.dp)
                .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style =
                TextStyle(
                    color = Color.White,
                    fontSize = BUTTON_FONT_SP.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
        )
    }
}

private const val BUTTON_SIZE_DP = 44
private const val BUTTON_CORNER_DP = 8
private const val BAR_CORNER_DP = 12
private const val BUTTON_GAP_DP = 4
private const val BUTTON_FONT_SP = 18
private const val DISABLED_ALPHA = 0.4f
private const val WIDE_BUTTON_PADDING_DP = 10
