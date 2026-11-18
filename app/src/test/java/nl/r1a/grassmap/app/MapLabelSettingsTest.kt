package nl.r1a.grassmap.app

import nl.r1a.grassmap.map.LabelLanguage
import nl.r1a.grassmap.map.LabelOptions
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class MapLabelSettingsTest {
    private fun tempFile(): File = File.createTempFile("map-labels", ".json").also { it.deleteOnExit() }

    @Test
    fun nothingSavedMeansTheStyleAsAuthored() {
        assertEquals(LabelOptions(), MapLabelSettings(file = null).options.value)
    }

    @Test
    fun aChangeReachesTheFlowAtOnce() {
        val settings = MapLabelSettings(file = null)

        settings.set(LabelOptions(LabelLanguage.English, 150))

        assertEquals(LabelOptions(LabelLanguage.English, 150), settings.options.value)
    }

    @Test
    fun theChoicePersistsAcrossANewInstance() {
        val file = tempFile()
        MapLabelSettings(file).set(LabelOptions(LabelLanguage.Ukrainian, 125))

        assertEquals(LabelOptions(LabelLanguage.Ukrainian, 125), MapLabelSettings(file).options.value)
        assertEquals("""{"language":"uk","sizePercent":125}""", file.readText())
    }

    @Test
    fun aSizeOutsideTheRangeIsRejectedAndChangesNothing() {
        val settings = MapLabelSettings(file = null)

        val outcome = settings.set(LabelOptions(sizePercent = LabelOptions.MAX_SIZE_PERCENT + 1))

        assertEquals(Outcome.Failure(AppError.InvalidInput), outcome)
        assertEquals(LabelOptions(), settings.options.value)
    }

    @Test
    fun anUnknownValueFallsBackToTheDefaultPartByPart() {
        val file = tempFile()
        file.writeText("""{"language":"fr","sizePercent":150}""")
        assertEquals(LabelOptions(LabelLanguage.Default, 150), MapLabelSettings(file).options.value)

        file.writeText("""{"language":"en","sizePercent":9000}""")
        assertEquals(LabelOptions(LabelLanguage.English), MapLabelSettings(file).options.value)

        file.writeText("{not json")
        assertEquals(LabelOptions(), MapLabelSettings(file).options.value)
    }
}
