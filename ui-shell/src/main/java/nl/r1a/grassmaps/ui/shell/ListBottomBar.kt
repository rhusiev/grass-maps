package nl.r1a.grassmaps.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmaps.plugin.ui.api.ShellColors
import nl.r1a.grassmaps.plugin.ui.api.ShellIcon
import nl.r1a.grassmaps.plugin.ui.api.ShellIcons

/** One round action of a list screen's bottom bar. */
internal class ListBarButton(
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * The bottom bar of a list screen (round 8 `list_bar`): search inside the list,
 * a divider, then the screen's often-tapped actions. The empty search reads the
 * count, as the canon draws it, and wraps instead of clipping at large font
 * scales. Every action is a 48 dp target with its spoken label.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun ListBottomBar(
    colors: ShellColors,
    placeholder: String,
    query: String,
    onQueryChange: (String) -> Unit,
    buttons: List<ListBarButton>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .floating(colors, RoundedCornerShape(BAR_CORNER_DP.dp))
                .background(colors.surface, RoundedCornerShape(BAR_CORNER_DP.dp))
                .padding(BAR_PADDING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp),
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(color = colors.ink, fontSize = LABEL_FONT_SP.sp),
            cursorBrush = SolidColor(colors.ink),
            modifier =
                Modifier
                    .weight(WEIGHT_ONE)
                    .heightIn(min = MIN_TAP_TARGET_DP.dp)
                    .semantics { contentDescription = placeholder },
            decorationBox = { field ->
                Row(
                    modifier = Modifier.padding(start = SEARCH_INSET_DP.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp),
                ) {
                    ShellIcon(icon = ShellIcons.Search, tint = colors.ink, modifier = Modifier.size(ICON_DP.dp))
                    Box(modifier = Modifier.weight(WEIGHT_ONE), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            BasicText(text = placeholder, style = TextStyle(color = colors.muted, fontSize = LABEL_FONT_SP.sp))
                        }
                        field()
                    }
                }
            },
        )
        if (buttons.isNotEmpty()) {
            Box(modifier = Modifier.width(1.dp).heightIn(min = DIVIDER_DP.dp).background(colors.line))
        }
        buttons.forEach { button ->
            RoundIconButton(
                colors = colors,
                icon = button.icon,
                label = button.label,
                enabled = button.enabled,
                onClick = button.onClick,
            )
        }
    }
}

/** A 48 dp round icon control with its spoken label; the glyph is decoration. */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun RoundIconButton(
    colors: ShellColors,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    filled: Boolean = true,
    tint: Color? = null,
) {
    Box(
        modifier =
            Modifier
                .size(MIN_TAP_TARGET_DP.dp)
                .background(if (filled) colors.soft else Color.Transparent, CircleShape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        ShellIcon(
            icon = icon,
            tint = if (enabled) tint ?: colors.ink else colors.muted.copy(alpha = DISABLED_ALPHA),
            modifier = Modifier.size(ICON_DP.dp),
        )
    }
}

/**
 * A floating control's edge: a shadow on the light theme, and the faint ring on
 * the dark one, where a shadow vanishes (round 9 dark tokens).
 */
internal fun Modifier.floating(
    colors: ShellColors,
    shape: RoundedCornerShape,
): Modifier =
    if (colors.dark) {
        border(1.dp, colors.ring, shape)
    } else {
        shadow(FLOAT_ELEVATION_DP.dp, shape)
    }

/** A full list screen's background: white on light, the dark map tone on dark (round 9 `k-saved`). */
internal fun listScreenBackground(colors: ShellColors): Color = if (colors.dark) colors.map else colors.surface

private const val MIN_TAP_TARGET_DP = 48
private const val ICON_DP = 22
private const val GAP_DP = 8
private const val SEARCH_INSET_DP = 8
private const val BAR_PADDING_DP = 5
private const val BAR_CORNER_DP = 29
private const val DIVIDER_DP = 28
private const val FLOAT_ELEVATION_DP = 6
private const val LABEL_FONT_SP = 16
private const val WEIGHT_ONE = 1f
private const val DISABLED_ALPHA = 0.5f
