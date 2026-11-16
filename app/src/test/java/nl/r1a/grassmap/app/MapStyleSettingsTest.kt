package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugins.layers.panel.MapStyleChoice
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class MapStyleSettingsTest {
    private fun tempFile(): File = File.createTempFile("style-settings", ".json").also { it.deleteOnExit() }

    @Test
    fun theMapStartsOnMeadowWhenNothingWasSaved() {
        assertEquals(MapStyleChoice.STANDARD, MapStyleSettings(file = null).current())
    }

    @Test
    fun switchingToTrailChangesTheActiveStyleState() {
        val settings = MapStyleSettings(file = null)

        assertEquals(Outcome.Success(Unit), settings.select(MapStyleChoice.TOURING))

        assertEquals(MapStyleChoice.TOURING, settings.active.value)
    }

    @Test
    fun theChoicePersistsAcrossANewInstance() {
        val file = tempFile()
        MapStyleSettings(file).select(MapStyleChoice.TOURING)

        assertEquals(MapStyleChoice.TOURING, MapStyleSettings(file).current())
        assertEquals("""{"style":"touring"}""", file.readText())
    }

    @Test
    fun anUnknownOrCorruptValueFallsBackToMeadow() {
        val file = tempFile()
        file.writeText("""{"style":"touring-dark"}""")
        assertEquals(MapStyleChoice.STANDARD, MapStyleSettings(file).current())

        file.writeText("{not json")
        assertEquals(MapStyleChoice.STANDARD, MapStyleSettings(file).current())
    }
}
