package nl.r1a.grassmap.plugins.manager

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import nl.r1a.grassmap.plugin.ui.api.LocalShellColors

internal val SURFACE: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.map

internal val CARD: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.surface

internal val INK: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.ink

internal val MUTED: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.muted

internal val ACCENT: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.accent

internal val DANGER: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.danger

internal val DANGER_TINT: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.danger.copy(alpha = DANGER_TINT_ALPHA)

internal val BORDER: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.line

internal val SWITCH_OFF: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.soft

internal val SWITCH_LINE: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.offSwitchBorder

internal val SCRIM: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.scrim

private const val DANGER_TINT_ALPHA = 0.14f

internal const val MAX_WIDTH_DP = 640
internal const val SCREEN_PADDING_DP = 16
internal const val GAP_DP = 12
internal const val SPACED_DP = 6
internal const val CARD_CORNER_DP = 16
internal const val CARD_PADDING_DP = 14
internal const val ROW_PADDING_DP = 12
internal const val BACK_TOUCH_DP = 8
internal const val TITLE_SP = 22
internal const val BODY_SP = 14
internal const val CAPTION_SP = 12
internal const val NAME_SP = 16
internal const val BACK_GLYPH_SP = 28
internal const val BACK_GLYPH = "‹"
internal const val SWITCH_WIDTH_DP = 48
internal const val SWITCH_HEIGHT_DP = 28
internal const val SWITCH_KNOB_DP = 22
internal const val SWITCH_INSET_DP = 3
internal const val SWITCH_BORDER_DP = 2
internal const val PILL_CORNER_DP = 24
internal const val SHEET_CORNER_DP = 24
