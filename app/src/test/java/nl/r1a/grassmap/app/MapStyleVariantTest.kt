package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.model.StyleId
import nl.r1a.grassmap.plugins.layers.panel.MapStyleChoice
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The map variant is chosen by the theme, not by the sheet over it.
 *
 * Route preview, the Stops sheet and the navigation screen are states of one
 * production map screen, so they cannot load a different variant. The dark
 * captures used to force `standard-dark` through the debug command while the
 * app itself kept the light base under dark chrome; this table is the app's
 * own path now.
 */
class MapStyleVariantTest {
    @Test
    fun routePreviewAndStopsTakeTheDarkVariantInTheDarkTheme() {
        val preview = mapStyleId(MapStyleChoice.STANDARD, dark = true)
        val stops = mapStyleId(MapStyleChoice.STANDARD, dark = true)

        assertEquals(StyleId("standard-dark"), preview)
        assertEquals("both screens are one map screen and one variant", preview, stops)
    }

    @Test
    fun trailKeepsItsOwnDarkVariant() {
        assertEquals(StyleId("touring-dark"), mapStyleId(MapStyleChoice.TOURING, dark = true))
        assertEquals(StyleId("touring"), mapStyleId(MapStyleChoice.TOURING, dark = false))
    }

    @Test
    fun theLightThemeKeepsTheLightVariants() {
        assertEquals(StyleId("standard"), mapStyleId(MapStyleChoice.STANDARD, dark = false))
        assertEquals(StyleId("standard-dark"), mapStyleId(MapStyleChoice.STANDARD, dark = true))
    }

    @Test
    fun aDarkSystemWithoutAPinChoosesTheDarkVariant() {
        val dark = isDarkTheme(fixture = null, override = null, systemDark = true)

        assertEquals("the system night mode is the default", true, dark)
        assertEquals(StyleId("standard-dark"), mapStyleId(MapStyleChoice.STANDARD, dark))
        assertEquals(StyleId("touring-dark"), mapStyleId(MapStyleChoice.TOURING, dark))
    }

    @Test
    fun theDebugPinWinsOverTheSystemAndTheCaptureWinsOverBoth() {
        assertEquals(false, isDarkTheme(fixture = null, override = false, systemDark = true))
        assertEquals(true, isDarkTheme(fixture = null, override = true, systemDark = false))
        assertEquals(true, isDarkTheme(fixture = true, override = false, systemDark = false))
        assertEquals(false, isDarkTheme(fixture = false, override = true, systemDark = true))
    }
}
