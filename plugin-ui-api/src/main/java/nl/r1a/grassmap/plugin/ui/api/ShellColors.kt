package nl.r1a.grassmap.plugin.ui.api

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The colour tokens every screen of the shell draws from (T-0612).
 *
 * Light values are the light canon from the design language; dark values are
 * round 9, `design-handoff-2026-11-13.md` section "Round 9 - dark theme of the
 * main screens". A screen asks [shellColors] for its theme and never keeps its
 * own copy, so the next screen cannot reinvent a value.
 *
 * T-0636 measured every text and boundary pair in both themes. Six values were
 * below their WCAG target and moved the minimum that reached it; each one says
 * its old value, new value and measured ratio in its own KDoc. A value that
 * passes is left at the handoff's figure.
 *
 * Floating surfaces take [ring] instead of a shadow, because a shadow vanishes
 * on the dark map. [selectedChip] is the inverted fill of a selected chip and
 * [inkOn] picks its text. [warning] is the amber card and its list.
 */
data class ShellColors(
    /** The screen background behind the map: `#1b1f24` in dark, the sand page in light. */
    val map: Color,
    /** Sheets, bars and cards: `#24282d` in dark. */
    val surface: Color,
    /** Chips, fields and grouped tiles: `#2f343a` in dark. */
    val soft: Color,
    /** Floating read-only glass: `rgba(36,40,45,.92)` in dark. */
    val glass: Color,
    /** Primary text and icons: `#e8eaed` in dark. */
    val ink: Color,
    /**
     * Secondary text: `#9aa0a6` in dark, `#5f6368` in light. The light canon's
     * `#686c72` measured 4.24:1 on [map], below the 4.5:1 body-text target, and
     * moved to `#5f6368` (4.86:1 on map, 6.05:1 on surface).
     */
    val muted: Color,
    /** Borders and dividers: `#474d54` in dark. */
    val line: Color,
    /**
     * Quiet chevrons and dotted connectors. Light `#807a72`, not the canon
     * `#b5b1aa`, which measured 2.14:1 on [surface] and 1.71:1 on [map],
     * below the 3:1 icon target. The new value is 4.25:1 and 3.41:1. Dark moves
     * from `#6f757c` to `#7a8087` for the same reason on [soft] (2.70:1 to
     * 3.15:1); its surface and map pairs were already above 3:1.
     */
    val faint: Color,
    /** A sheet's drag handle. */
    val handle: Color,
    /**
     * Accent text and switches: `#5fd39a` in dark, `#1a734b` in light. The
     * light canon's `#1e7f53` measured 4.41:1 on [soft], 4.00:1 on [map] and
     * 4.13:1 on a 14% accent tint, all below 4.5:1; `#1a734b` is 5.17:1,
     * 4.69:1 and 4.78:1.
     */
    val accent: Color,
    /** Text and switch knobs on an accent or ink fill. */
    val onAccent: Color,
    /** Error text and the End button's ink: `#f28b82` in dark. */
    val danger: Color,
    /** The End button's fill. */
    val dangerSoft: Color,
    val warning: WarningColors,
    /**
     * The outline of a control whose fill alone is not its boundary: the off
     * switch's track, a quiet button, a text field. `#80868b` in dark. It must
     * reach 3:1 against [surface], [soft], [map] and [glass].
     *
     * Light is `#857f77`, not the light canon `#b9b4ac`: that value reached only
     * 2.06:1 on [surface] and 1.66:1 on [map], below the 3:1 boundary target.
     */
    val controlBorder: Color,
    /** The off switch's knob. */
    val offSwitchKnob: Color,
    /**
     * The faint 1 px ring around a floating surface: light in dark, where a
     * shadow vanishes; a faint dark ring in light, where this shell draws no
     * shadow.
     */
    val ring: Color,
    /** The dim layer behind a modal card. */
    val scrim: Color,
    /** The fill of a selected chip; its text is [inkOn] of this fill. */
    val selectedChip: Color,
    /** A chart's hairline grid. */
    val grid: Color,
    /** The in-app keyboard's background and its keys. */
    val keyboard: Color,
    val key: Color,
    val keySoft: Color,
    val keyLine: Color,
    /**
     * A warm score accent, used where a number is a rating rather than a
     * warning. Light is `#8a5a00`, not the round 8 `#d08a00`: that value
     * measured 2.87:1 on [surface] and 2.30:1 on [map], below the 4.5:1 text
     * target used by the changed-value rows. The new value is 5.93:1 and
     * 4.76:1.
     */
    val amber: Color,
    val profiles: ProfileColors,
    val categories: CategoryColors,
    val dark: Boolean,
)

/** The warning card and the warning list drawn from it. */
data class WarningColors(
    /** `#413b27` in dark. */
    val surface: Color,
    /** `#c99a12` in dark. */
    val border: Color,
    /** `#ffd970` in dark. */
    val title: Color,
    /** The secondary line inside the card. */
    val text: Color,
)

/** A travel profile's colour, light and dark. */
data class ProfileColors(
    val walk: Color,
    val bike: Color,
    val hike: Color,
    val stroller: Color,
)

/** Place and layer category colours, lifted to about 5:1 on the dark surface. */
data class CategoryColors(
    val pharmacy: Color,
    val theater: Color,
    val coffee: Color,
    val closingSoon: Color,
    val pin: Color,
    val hiking: Color,
    val contours: Color,
    val satellite: Color,
    val night: Color,
)

private val LIGHT_WARNING =
    WarningColors(
        surface = Color(0xFFFFF8E1),
        border = Color(0xFFF2B200),
        title = Color(0xFF5C4300),
        text = Color(0xFF7A5D00),
    )

private val DARK_WARNING =
    WarningColors(
        surface = Color(0xFF413B27),
        border = Color(0xFFC99A12),
        title = Color(0xFFFFD970),
        text = Color(0xFFE3C46E),
    )

/** The light tokens: the light canon of `design-language.md`. */
val LightShellColors: ShellColors =
    ShellColors(
        map = Color(0xFFE9E6E0),
        surface = Color(0xFFFFFFFF),
        soft = Color(0xFFF2F1EE),
        glass = Color(0xE0FFFFFF),
        ink = Color(0xFF202124),
        muted = Color(0xFF5F6368),
        line = Color(0xFFEBE8E3),
        faint = Color(0xFF807A72),
        handle = Color(0xFFCFCCC6),
        accent = Color(0xFF1A734B),
        onAccent = Color(0xFFFFFFFF),
        danger = Color(0xFFB3261E),
        dangerSoft = Color(0xFFFDECEA),
        warning = LIGHT_WARNING,
        controlBorder = Color(0xFF857F77),
        offSwitchKnob = Color(0xFF8F8A82),
        ring = Color(0x12000000),
        scrim = Color(0x66000000),
        selectedChip = Color(0xFF202124),
        grid = Color(0x14000000),
        keyboard = Color(0xFFECEEF1),
        key = Color(0xFFFFFFFF),
        keySoft = Color(0xFFD5D9DF),
        keyLine = Color(0xFFDFE2E6),
        amber = Color(0xFF8A5A00),
        profiles =
            ProfileColors(
                walk = Color(0xFF2A78D6),
                bike = Color(0xFFEB6834),
                hike = Color(0xFF1B8A3A),
                stroller = Color(0xFF8A5CC7),
            ),
        categories =
            CategoryColors(
                pharmacy = Color(0xFF1A8B6F),
                theater = Color(0xFF9C4FB5),
                coffee = Color(0xFFA0522D),
                closingSoon = Color(0xFFA86400),
                pin = Color(0xFFD93025),
                hiking = Color(0xFFD6336C),
                contours = Color(0xFFA86B2D),
                satellite = Color(0xFF4D7A3A),
                night = Color(0xFF3B3F6B),
            ),
        dark = false,
    )

/** The dark tokens: round 9 of `design-handoff-2026-11-13.md`. */
val DarkShellColors: ShellColors =
    ShellColors(
        map = Color(0xFF1B1F24),
        surface = Color(0xFF24282D),
        soft = Color(0xFF2F343A),
        glass = Color(0xEB24282D),
        ink = Color(0xFFE8EAED),
        muted = Color(0xFF9AA0A6),
        line = Color(0xFF474D54),
        faint = Color(0xFF7A8087),
        handle = Color(0xFF55595E),
        accent = Color(0xFF5FD39A),
        onAccent = Color(0xFF1B1F24),
        danger = Color(0xFFF28B82),
        dangerSoft = Color(0xFF4A2524),
        warning = DARK_WARNING,
        controlBorder = Color(0xFF80868B),
        offSwitchKnob = Color(0xFF80868B),
        ring = Color(0x12FFFFFF),
        scrim = Color(0x66000000),
        selectedChip = Color(0xFFE8EAED),
        grid = Color(0x1AFFFFFF),
        keyboard = Color(0xFF15181C),
        key = Color(0xFF31363C),
        keySoft = Color(0xFF26292E),
        keyLine = Color(0xFF2F343A),
        amber = Color(0xFFC99A12),
        profiles =
            ProfileColors(
                walk = Color(0xFF5AA0EF),
                bike = Color(0xFFF07A45),
                hike = Color(0xFF3FBF6A),
                stroller = Color(0xFFA98BE6),
            ),
        categories =
            CategoryColors(
                pharmacy = Color(0xFF21B08C),
                theater = Color(0xFFBC88CD),
                coffee = Color(0xFFD48865),
                closingSoon = Color(0xFFE08500),
                pin = Color(0xFFE77C75),
                hiking = Color(0xFFE4799E),
                contours = Color(0xFFCF8D4B),
                satellite = Color(0xFF6DAC52),
                night = Color(0xFF9498C4),
            ),
        dark = true,
    )

/** The token set of [dark], or [LightShellColors] when the platform does not ask for dark. */
fun shellColors(dark: Boolean): ShellColors = if (dark) DarkShellColors else LightShellColors

/**
 * The text colour for a fill that inverts: the palette's ink or its on-accent
 * colour, whichever reads better against [fill].
 */
fun inkOn(
    fill: Color,
    colors: ShellColors = LightShellColors,
): Color =
    if (contrastRatio(colors.ink, fill) >= contrastRatio(colors.onAccent, fill)) {
        colors.ink
    } else {
        colors.onAccent
    }

/** The WCAG 2.1 contrast ratio of [foreground] on [background], from 1 to 21. */
fun contrastRatio(
    foreground: Color,
    background: Color,
): Double {
    val light = max(luminance(foreground), luminance(background))
    val dark = min(luminance(foreground), luminance(background))
    return (light + CONTRAST_OFFSET) / (dark + CONTRAST_OFFSET)
}

private const val CONTRAST_OFFSET = 0.05

private fun luminance(color: Color): Double {
    val red = linear(color.red.toDouble())
    val green = linear(color.green.toDouble())
    val blue = linear(color.blue.toDouble())
    return LUMINANCE_RED * red + LUMINANCE_GREEN * green + LUMINANCE_BLUE * blue
}

private fun linear(channel: Double): Double {
    if (channel <= SRGB_KNEE) {
        return channel / SRGB_SLOPE
    }
    return ((channel + SRGB_OFFSET) / SRGB_DIVISOR).pow(2.4)
}

private const val SRGB_KNEE = 0.04045
private const val SRGB_SLOPE = 12.92
private const val SRGB_OFFSET = 0.055
private const val SRGB_DIVISOR = 1.055
private const val LUMINANCE_RED = 0.2126
private const val LUMINANCE_GREEN = 0.7152
private const val LUMINANCE_BLUE = 0.0722

/**
 * The token set of the composition. [nl.r1a.grassmap.ui.shell.GrassMapsTheme]
 * provides it; a screen outside the theme draws in light, so a preview or a
 * component test needs no provider.
 */
val LocalShellColors = staticCompositionLocalOf { LightShellColors }
