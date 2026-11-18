package nl.r1a.grassmap.plugins.profiles.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal val SURFACE = Color(0xFFFFFFFF)
internal val INK = Color(0xFF1F2329)
internal val MUTED = Color(0xFF5D6168)
internal val ACCENT = Color(0xFF1F7A4D)
internal val ACCENT_TINT = Color(0xFFECF4EF)
internal val ON_ACCENT = Color(0xFFFFFFFF)
internal val NEUTRAL_BUTTON = Color(0xFFF1F0ED)
internal val FIELD = Color(0xFFF1F0ED)
internal val SCRIM = Color(0x66000000)
internal val SNACKBAR = Color(0xFF33363B)
internal val SNACKBAR_TEXT = Color(0xFFFFFFFF)
internal val SNACKBAR_ACTION = Color(0xFF8FD5A8)
internal val RADIO_OFF = Color(0xFF8A8D93)

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

internal val TITLE_STYLE = TextStyle(color = INK, fontSize = 26.sp, fontWeight = FontWeight.Bold)
internal val ROW_TITLE_STYLE = TextStyle(color = INK, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
internal val BODY_STYLE = TextStyle(color = MUTED, fontSize = 14.sp)
internal val LABEL_STYLE = TextStyle(color = MUTED, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
internal val BUTTON_STYLE = TextStyle(color = INK, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
