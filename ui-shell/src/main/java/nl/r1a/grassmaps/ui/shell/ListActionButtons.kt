package nl.r1a.grassmaps.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val CORNER_DP = 14
private const val PADDING_DP = 14
private const val PILL_PADDING_DP = 8

/**
 * The one filled or soft action of a list panel, shared by the Saved panels and
 * the Tracks sheets. [fill] spans the panel; otherwise the button sizes to its
 * label. The whole box is the target, and its height grows with the text at a
 * large font scale.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun ListActionButton(
    text: String,
    background: Color,
    ink: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    corner: Dp = CORNER_DP.dp,
    fill: Boolean = false,
) {
    Box(
        modifier =
            modifier
                .then(if (fill) Modifier.fillMaxWidth() else Modifier.widthIn(min = MapControlMetrics.TAP_TARGET_DP.dp))
                .heightIn(min = MapControlMetrics.TAP_TARGET_DP.dp)
                .background(
                    if (enabled) background else background.copy(alpha = ListChromeMetrics.DISABLED_ALPHA),
                    RoundedCornerShape(corner),
                ).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = PADDING_DP.dp, vertical = PILL_PADDING_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = TextStyle(color = ink, fontSize = ListChromeMetrics.LABEL_FONT_SP.sp, fontWeight = FontWeight.SemiBold),
        )
    }
}

/**
 * The undo pill of a list: one message and one action in a tinted row. Saved
 * passes an empty message when the label itself is the whole notice; Tracks
 * names what the action reverses.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun ListUndoBar(
    message: String,
    undo: String,
    background: Color,
    ink: Color,
    action: Color,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .background(background, RoundedCornerShape(CORNER_DP.dp))
                .padding(PADDING_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(PADDING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (message.isNotBlank()) {
            BasicText(
                text = message,
                style = TextStyle(color = ink, fontSize = ListChromeMetrics.LABEL_FONT_SP.sp),
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        ListActionButton(
            text = undo,
            background = Color.Transparent,
            ink = action,
            onClick = onUndo,
            corner = CORNER_DP.dp,
        )
    }
}
