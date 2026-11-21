package nl.r1a.grassmaps.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import nl.r1a.grassmaps.plugin.ui.api.DarkShellColors
import nl.r1a.grassmaps.plugin.ui.api.LightShellColors
import nl.r1a.grassmaps.plugin.ui.api.NavigationScreenStatus
import nl.r1a.grassmaps.plugin.ui.api.ShellColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one decision the map screen's status-bar owner makes. The regression this
 * guards is a second writer: a theme change used to re-run a separate effect
 * that put the map token back over the navigation card colour. Here both inputs
 * go into one pure function, so the ordering of two window writes cannot change
 * the answer. The exact ordering of Compose effects across a recomposition
 * needs a device or an emulator and is not asserted here.
 */
class StatusBarColorsTest {
    @Test
    fun aFollowingScreenTakesTheGreenCardColourInBothThemes() {
        for (colors in THEMES) {
            assertEquals(FOLLOWING_GREEN, statusBarSpec(NavigationScreenStatus.Following, colors).argb)
        }
    }

    @Test
    fun everyNavigationStateKeepsItsOwnCardColour() {
        assertEquals(FOLLOWING_GREEN, navigationStatusBarColor(NavigationScreenStatus.Following).toArgb())
        assertEquals(FOLLOWING_GREEN, navigationStatusBarColor(NavigationScreenStatus.Arrived).toArgb())
        assertEquals(OFF_ROUTE_AMBER, navigationStatusBarColor(NavigationScreenStatus.OffRoute).toArgb())
        assertEquals(PAUSED_GREY, navigationStatusBarColor(NavigationScreenStatus.Paused).toArgb())
    }

    @Test
    fun aThemeSwitchKeepsTheNavigationColour() {
        val light = statusBarSpec(NavigationScreenStatus.Following, LightShellColors)
        val dark = statusBarSpec(NavigationScreenStatus.Following, DarkShellColors)

        assertEquals(light.argb, dark.argb)
        assertEquals(FOLLOWING_GREEN, dark.argb)
    }

    @Test
    fun withNoNavigationScreenTheMapTokenAndTheThemeFlagWin() {
        val light = statusBarSpec(null, LightShellColors)
        val dark = statusBarSpec(null, DarkShellColors)

        assertEquals(LightShellColors.map.toArgb(), light.argb)
        assertTrue(light.lightIcons)
        assertEquals(DarkShellColors.map.toArgb(), dark.argb)
        assertFalse(dark.lightIcons)
    }

    @Test
    fun aColouredBarClearsTheLightIconFlagInBothThemes() {
        for (colors in THEMES) {
            assertFalse(statusBarSpec(NavigationScreenStatus.OffRoute, colors).lightIcons)
        }
    }

    private companion object {
        val FOLLOWING_GREEN = Color(0xFF1E7F53).toArgb()
        val OFF_ROUTE_AMBER = Color(0xFF8A5A00).toArgb()
        val PAUSED_GREY = Color(0xFF5F6368).toArgb()
        val THEMES: List<ShellColors> = listOf(LightShellColors, DarkShellColors)
    }
}
