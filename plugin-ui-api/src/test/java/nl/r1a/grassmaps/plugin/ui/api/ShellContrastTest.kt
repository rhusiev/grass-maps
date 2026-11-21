package nl.r1a.grassmaps.plugin.ui.api

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The measured contrast table of the shell (T-0636).
 *
 * Every pair the shell draws for text, icons and a control boundary is listed
 * with the component that uses it and the WCAG 2.1 target it must reach: 4.5:1
 * for normal text, 3:1 for large text and for a component boundary or icon.
 * The ratios are computed from [LightShellColors] and [DarkShellColors], so a
 * changed token is caught here without a device or a screenshot.
 *
 * The table also holds the round 5 turn-card and info-banner colours, which do
 * not change with the theme. A card is not a boundary pair: its content
 * identifies it. The outline entries cover the controls whose fill alone was
 * not a boundary: quiet buttons, tonal chips, fields and off switches, plus the
 * End and primary fills. The scrim only dims the map behind a modal card, so it
 * has no foreground of its own; ink over it is recorded as the only plausible
 * pair.
 */
class ShellContrastTest {
    @Test
    fun everyDeclaredPairReachesItsTargetInBothThemes() {
        val failures = mutableListOf<String>()
        var checked = 0
        for ((theme, colors) in listOf("light" to LightShellColors, "dark" to DarkShellColors)) {
            for (pair in pairs(colors)) {
                checked += 1
                check(theme, pair)?.let(failures::add)
            }
        }
        for (pair in fixedPairs()) {
            checked += 1
            check("both themes", pair)?.let(failures::add)
        }
        assertTrue(
            buildString {
                append(failures.size)
                append(" of ")
                append(checked)
                append(" shell colour pairs are below their target:")
                failures.forEach { failure ->
                    append('\n')
                    append(failure)
                }
            },
            failures.isEmpty(),
        )
    }

    private fun check(
        theme: String,
        pair: ShellPair,
    ): String? {
        val ratio = contrastRatio(pair.foreground, pair.background)
        if (ratio + RATIO_EPSILON >= pair.minimum) {
            return null
        }
        return String.format(
            Locale.US,
            "%s: %s is %.2f:1, below %.1f:1",
            theme,
            pair.usage,
            ratio,
            pair.minimum,
        )
    }

    private fun pairs(colors: ShellColors): List<ShellPair> {
        val glassOnMap = colors.glass.compositeOver(colors.map)

        fun tint(
            colour: Color,
            alpha: Float,
        ): Color = colour.copy(alpha = alpha).compositeOver(colors.surface)
        return listOf(
            // Text.
            ShellPair("map page title and header", colors.ink, colors.map, BODY_TEXT),
            ShellPair("sheet row and card text", colors.ink, colors.surface, BODY_TEXT),
            ShellPair("chip, field and speed-tile text", colors.ink, colors.soft, BODY_TEXT),
            ShellPair("credit, scale bar and glass-panel text", colors.ink, glassOnMap, BODY_TEXT),
            ShellPair("downloads section label on the page", colors.muted, colors.map, BODY_TEXT),
            ShellPair("sheet row detail and card secondary text", colors.muted, colors.surface, BODY_TEXT),
            ShellPair("off channel chip label", colors.muted, colors.soft, BODY_TEXT),
            ShellPair("search hint and glass-chip detail", colors.muted, glassOnMap, BODY_TEXT),
            ShellPair("accent link over the map page (token floor, no current call site)", colors.accent, colors.map, BODY_TEXT),
            ShellPair("accent status and link on a card", colors.accent, colors.surface, BODY_TEXT),
            ShellPair("selected folder and channel label on a soft chip", colors.accent, colors.soft, BODY_TEXT),
            ShellPair("accent text on the glass search panel (token floor)", colors.accent, glassOnMap, BODY_TEXT),
            ShellPair("danger notice over the map page (token floor)", colors.danger, colors.map, BODY_TEXT),
            ShellPair("problem line on a card", colors.danger, colors.surface, BODY_TEXT),
            ShellPair("danger on a soft tile", colors.danger, colors.soft, BODY_TEXT),
            ShellPair("End pill and region-problem banner label", colors.danger, colors.dangerSoft, BODY_TEXT),
            ShellPair("primary label: Start, Apply, Download, Follow, Import, Done", colors.onAccent, colors.accent, BODY_TEXT),
            ShellPair("selected chip label", inkOn(colors.selectedChip, colors), colors.selectedChip, BODY_TEXT),
            ShellPair("warning title on the warning card", colors.warning.title, colors.warning.surface, BODY_TEXT),
            ShellPair("warning body on the warning card", colors.warning.text, colors.warning.surface, BODY_TEXT),
            ShellPair("changed-value text on a card", colors.amber, colors.surface, BODY_TEXT),
            ShellPair("changed-value text on the map page", colors.amber, colors.map, BODY_TEXT),
            ShellPair("channel chip label on the 14% accent tint", colors.accent, tint(colors.accent, 0.14f), BODY_TEXT),
            ShellPair("navigation alert chip label on the 16% accent tint", colors.accent, tint(colors.accent, 0.16f), BODY_TEXT),
            ShellPair("selected folder label on the 12% accent tint", colors.accent, tint(colors.accent, 0.12f), BODY_TEXT),
            ShellPair("ink over the modal scrim", colors.ink, colors.scrim.compositeOver(colors.map), BODY_TEXT),
            ShellPair(
                "undo snackbar label on the inverse surface",
                inkOn(colors.snackbarBackground, colors),
                colors.snackbarBackground,
                BODY_TEXT,
            ),
            ShellPair(
                "undo snackbar action on the inverse surface",
                colors.snackbarAction,
                colors.snackbarBackground,
                BODY_TEXT,
            ),
            // Boundaries and icons.
            ShellPair("End pill outline on the bar", colors.danger, colors.surface, UI_COMPONENT),
            ShellPair("primary fill on the card", colors.accent, colors.surface, UI_COMPONENT),
            ShellPair("quiet button and field outline on a card", colors.controlBorder, colors.surface, UI_COMPONENT),
            ShellPair("off switch outline on its soft track", colors.controlBorder, colors.soft, UI_COMPONENT),
            ShellPair("search field outline on the map page", colors.controlBorder, colors.map, UI_COMPONENT),
            ShellPair("search field outline on the glass panel", colors.controlBorder, glassOnMap, UI_COMPONENT),
            ShellPair("alerts off-switch knob on the soft track", colors.offSwitchKnob, colors.soft, UI_COMPONENT),
            ShellPair("navigation off-switch knob on the soft track", colors.faint, colors.soft, UI_COMPONENT),
            ShellPair("chevron on a card", colors.faint, colors.surface, UI_COMPONENT),
            ShellPair("chevron on the map page", colors.faint, colors.map, UI_COMPONENT),
            ShellPair("chevron on glass", colors.faint, glassOnMap, UI_COMPONENT),
            ShellPair("favourite icon on its 10% amber tint", colors.amberIcon, tint(colors.amberIcon, 0.10f), UI_COMPONENT),
            ShellPair("neutral alert icon on its 10% muted tint", colors.muted, tint(colors.muted, 0.10f), UI_COMPONENT),
            ShellPair("progress fill on its track", colors.accent, colors.line, UI_COMPONENT),
            ShellPair("switch knob on the accent track", colors.onAccent, colors.accent, UI_COMPONENT),
        )
    }

    private fun fixedPairs(): List<ShellPair> =
        listOf(
            ShellPair("turn card content on the following fill", Color.White, Color(NavigationScreenColors.GREEN), BODY_TEXT),
            ShellPair(
                "turn card strip on the following strip",
                Color(NavigationScreenColors.GREEN_INK),
                Color(NavigationScreenColors.GREEN_STRIP),
                BODY_TEXT,
            ),
            ShellPair("turn card content on the off-route fill", Color.White, Color(NavigationScreenColors.OFF_ROUTE), BODY_TEXT),
            ShellPair("turn card strip on the off-route strip", Color.White, Color(NavigationScreenColors.OFF_ROUTE_STRIP), BODY_TEXT),
            ShellPair("turn card content on the paused fill", Color.White, Color(NavigationScreenColors.PAUSED), BODY_TEXT),
            ShellPair("turn card strip on the paused strip", Color.White, Color(NavigationScreenColors.PAUSED_STRIP), BODY_TEXT),
            ShellPair("info banner title", Color(NavigationScreenColors.INFO_INK), Color(NavigationScreenColors.INFO_BG), BODY_TEXT),
            ShellPair("info banner detail", Color(NavigationScreenColors.INFO_SUB), Color(NavigationScreenColors.INFO_BG), BODY_TEXT),
        )

    private data class ShellPair(
        val usage: String,
        val foreground: Color,
        val background: Color,
        val minimum: Double,
    )

    private companion object {
        const val BODY_TEXT = 4.5
        const val UI_COMPONENT = 3.0
        const val RATIO_EPSILON = 1e-9
    }
}
