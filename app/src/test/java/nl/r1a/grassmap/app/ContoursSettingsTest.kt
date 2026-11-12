package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ContoursSettingsTest {
    @Test
    fun contoursStartOnWhenNoSettingWasSaved() {
        val settings = ContoursSettings(file = null)

        assertTrue(settings.isEnabled())
        assertEquals(ContoursSettings.DEFAULT_ENABLED, settings.enabled.value)
    }

    @Test
    fun switchingOffPersistsAcrossANewInstance() {
        val file = File.createTempFile("contours-settings", ".json").also { it.deleteOnExit() }

        val first = ContoursSettings(file)
        assertEquals(Outcome.Success(Unit), first.setEnabled(false))

        val second = ContoursSettings(file)
        assertFalse(second.isEnabled())
        assertEquals("""{"enabled":false}""", file.readText())
    }

    @Test
    fun aCorruptSettingFallsBackToOn() {
        val file = File.createTempFile("contours-settings", ".json").also { it.deleteOnExit() }
        file.writeText("{not json")

        assertTrue(ContoursSettings(file).isEnabled())
    }

    @Test
    fun theGoldenOverrideWinsWithoutRewritingTheFile() {
        val file = File.createTempFile("contours-settings", ".json").also { it.deleteOnExit() }
        ContoursSettings(file).setEnabled(true)

        val overridden = ContoursSettings(file, override = { false })

        assertFalse(overridden.isEnabled())
        assertEquals("""{"enabled":true}""", file.readText())
    }

    @Test
    fun theGoldenExtraNameIsStable() {
        assertEquals("map.contours.enabled", ContoursSettings.EXTRA_ENABLED)
    }
}
