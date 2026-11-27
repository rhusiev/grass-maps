package nl.r1a.grassmaps.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import nl.r1a.grassmaps.plugin.ui.api.LocalShellColors
import nl.r1a.grassmaps.plugin.ui.api.inkOn

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

private const val WARNING_ICON_ALPHA = 0.2f
