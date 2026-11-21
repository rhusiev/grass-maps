package nl.r1a.grassmaps.plugins.contours

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Layers card for the contour overlay is drawn only while its plugin is on (T-0607). */
class ContoursPanelToggleTest {
    @Test
    fun aPluginThatIsOffHasNoRow() {
        assertNull(contoursPanelToggle(pluginEnabled = false, enabled = true))
        assertNull(contoursPanelToggle(pluginEnabled = false, enabled = false))
    }

    @Test
    fun aPluginThatIsOnKeepsTheUsersLayerSwitch() {
        assertEquals(true, contoursPanelToggle(pluginEnabled = true, enabled = true))
        assertEquals(false, contoursPanelToggle(pluginEnabled = true, enabled = false))
    }
}
