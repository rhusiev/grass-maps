package nl.r1a.grassmap.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nl.r1a.grassmap.plugin.ui.api.LocalShellColors
import nl.r1a.grassmap.plugin.ui.api.inkOn

/**
 * The colours the floating route panels share, so a panel and its sheet agree.
 *
 * Each value is read from the shell tokens when it is drawn, so one palette
 * serves the light and the dark theme and no panel keeps its own copy
 * (T-0612).
 */
internal val ROUTE_PANEL_BACKGROUND: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.surface

internal val ROUTE_VALUE_COLOR: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.ink

internal val ROUTE_STATUS_COLOR: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.muted

internal val ROUTE_DISABLED_COLOR: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.faint

internal val ROUTE_CHIP_COLOR: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.soft

/** The outline of a quiet chip, field or button whose fill is only tonal. */
internal val ROUTE_CONTROL_BORDER: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.controlBorder

/** A selected chip inverts to the ink fill. */
internal val ROUTE_CHIP_SELECTED_COLOR: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.selectedChip

/** The text on a selected chip, picked against its inverted fill. */
internal val ROUTE_CHIP_SELECTED_INK: Color
    @Composable @ReadOnlyComposable
    get() = inkOn(LocalShellColors.current.selectedChip, LocalShellColors.current)

internal val ROUTE_ACTION_COLOR: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.accent

/** The text on the action fill, picked against the accent. */
internal val ROUTE_ACTION_INK: Color
    @Composable @ReadOnlyComposable
    get() = inkOn(LocalShellColors.current.accent, LocalShellColors.current)

internal val ROUTE_CHANGED_COLOR: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.amber

/** The error tone the shell's notices use for a refusal (T-0416). */
internal val ROUTE_ERROR_COLOR: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.danger

/** The amber warning row and its list, from the round-6 design (T-0409). */
internal val ROUTE_WARNING_BACKGROUND: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.warning.surface

internal val ROUTE_WARNING_BORDER: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.warning.border

internal val ROUTE_WARNING_TITLE_COLOR: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.warning.title

internal val ROUTE_WARNING_SUMMARY_COLOR: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.warning.text

internal val ROUTE_WARNING_ICON_BACKGROUND: Color
    @Composable @ReadOnlyComposable
    get() =
        LocalShellColors.current.warning.border
            .copy(alpha = WARNING_ICON_ALPHA)

internal val ROUTE_WARNING_ICON_COLOR: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.warning.title

/**
 * A dashed rounded outline for a free layout slot (T-0606). It draws inside
 * the element's bounds, so the slot keeps its measured size.
 */
internal fun Modifier.dashedRing(
    color: Color,
    corner: Dp,
    stroke: Dp = 1.dp,
): Modifier =
    drawBehind {
        val strokePx = stroke.toPx()
        val half = strokePx / 2f
        drawRoundRect(
            color = color,
            topLeft = Offset(half, half),
            size = Size(size.width - strokePx, size.height - strokePx),
            cornerRadius = CornerRadius(corner.toPx().coerceAtMost(size.minDimension / 2f)),
            style =
                Stroke(
                    width = strokePx,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(strokePx * 3f, strokePx * 2f)),
                ),
        )
    }

private const val WARNING_ICON_ALPHA = 0.2f
