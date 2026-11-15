package nl.r1a.grassmap.app

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugins.raster.RasterPresets
import nl.r1a.grassmap.plugins.raster.RasterSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The persisted online-tile source and the probe that guards a custom one. */
class RasterSourceSettingsTest {
    @Test
    fun aNewStoreUsesTheOsmPreset() {
        val settings = RasterSourceSettings(file = null)

        assertEquals(RasterPresets.openStreetMap, settings.current())
    }

    @Test
    fun anInvalidTemplateNeverReachesTheProbe() =
        runTest {
            var probes = 0
            val settings = RasterSourceSettings(file = null)

            val outcome =
                settings.useCustom("https://t.example/plain.png") { _ ->
                    probes += 1
                    Outcome.Success(Unit)
                }

            assertEquals(Outcome.Failure(AppError.InvalidInput), outcome)
            assertEquals(0, probes)
            assertEquals(RasterPresets.openStreetMap, settings.current())
        }

    @Test
    fun aProbedCustomSourceBecomesActiveAndPersists() =
        runTest {
            val file = temporaryFile()
            val settings = RasterSourceSettings(file = file)
            val template = "https://t.example/{z}/{x}/{y}.png"

            val outcome = settings.useCustom(template) { source -> probed(source, template) }

            assertTrue(outcome is Outcome.Success)
            assertEquals(template, settings.current().tileUrlTemplate)
            assertTrue(file.readText(), file.readText().contains(template))
            assertEquals(template, RasterSourceSettings(file = file).current().tileUrlTemplate)
        }

    @Test
    fun aFailedProbeKeepsThePreviousSourceAndWritesNothing() =
        runTest {
            val file = temporaryFile()
            file.delete()
            val settings = RasterSourceSettings(file = file)

            val outcome =
                settings.useCustom("https://t.example/{z}/{x}/{y}.png") {
                    Outcome.Failure(AppError.Network)
                }

            assertEquals(Outcome.Failure(AppError.Network), outcome)
            assertEquals(RasterPresets.openStreetMap, settings.current())
            assertTrue(!file.exists())
        }

    @Test
    fun aCorruptFileFallsBackToTheOsmPreset() {
        val file = temporaryFile()
        file.writeText("not json")

        assertEquals(RasterPresets.openStreetMap, RasterSourceSettings(file = file).current())
    }

    private fun probed(
        source: RasterSource,
        template: String,
    ): Outcome<Unit> {
        assertEquals(template, source.tileUrlTemplate)
        return Outcome.Success(Unit)
    }

    private fun temporaryFile(): File = Files.createTempFile("raster-settings", ".json").toFile().also { it.deleteOnExit() }
}
