package nl.r1a.grassmaps.plugins.profiles.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import nl.r1a.grassmaps.plugin.ui.api.LocalShellColors
import nl.r1a.grassmaps.plugin.ui.api.inkOn

internal val SURFACE: Color
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

internal val ACCENT_TINT: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.accent.copy(alpha = ACCENT_TINT_ALPHA)

internal val ON_ACCENT: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.onAccent

internal val NEUTRAL_BUTTON: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.soft

internal val FIELD: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.soft

internal val SCRIM: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.scrim

internal val RADIO_OFF: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.controlBorder

internal val CONTROL_LINE: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.controlBorder

/** The snackbar's inverse-surface fill, shared by both themes. */
internal val SNACKBAR: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.snackbarBackground

/** The snackbar label: the token ink or on-accent, whichever reads on the fill. */
internal val SNACKBAR_TEXT: Color
    @Composable @ReadOnlyComposable
    get() = inkOn(LocalShellColors.current.snackbarBackground, LocalShellColors.current)

/** The light-mint action on the snackbar's inverse surface. */
internal val SNACKBAR_ACTION: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.snackbarAction

internal val TITLE_STYLE: TextStyle
    @Composable @ReadOnlyComposable
    get() = TextStyle(color = INK, fontSize = 26.sp, fontWeight = FontWeight.Bold)

internal val ROW_TITLE_STYLE: TextStyle
    @Composable @ReadOnlyComposable
    get() = TextStyle(color = INK, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)

internal val BODY_STYLE: TextStyle
    @Composable @ReadOnlyComposable
    get() = TextStyle(color = MUTED, fontSize = 14.sp)

internal val LABEL_STYLE: TextStyle
    @Composable @ReadOnlyComposable
    get() = TextStyle(color = MUTED, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

internal val BUTTON_STYLE: TextStyle
    @Composable @ReadOnlyComposable
    get() = TextStyle(color = INK, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

private const val ACCENT_TINT_ALPHA = 0.14f

internal const val SCREEN_PADDING_DP = 20
internal const val GAP_DP = 12
internal const val ROW_PADDING_DP = 14
internal const val BUTTON_HEIGHT_DP = 52
internal const val BUTTON_CORNER_DP = 26
internal const val SHEET_CORNER_DP = 24
internal const val FIELD_CORNER_DP = 20
internal const val RADIO_SIZE_DP = 22
internal const val RADIO_DOT_DP = 12
internal const val RADIO_STROKE_DP = 2
internal const val SNACKBAR_CORNER_DP = 14
internal const val SNACKBAR_MS = 5_000L
internal const val MAX_WIDTH_DP = 600
internal const val NAME_MAX_LENGTH = 40
internal const val CHEVRON = "›"
internal const val BACK_GLYPH = "‹"
internal const val CHECK_GLYPH = "✓"
