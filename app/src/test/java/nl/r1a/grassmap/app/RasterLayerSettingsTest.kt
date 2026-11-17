package nl.r1a.grassmap.app

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugins.raster.RasterPlacement
import nl.r1a.grassmap.plugins.raster.RasterPresets
import nl.r1a.grassmap.plugins.raster.RasterSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The persisted raster layer: its source, its placement and its transparency. */
class RasterLayerSettingsTest {
    @Test
    fun aNewStoreUsesTheOsmPresetOverTheBaseMap() {
        val settings = RasterLayerSettings(file = null)

        assertEquals(
            RasterLayerState(
                source = RasterPresets.openStreetMap,
                placement = RasterPlacement.Overlay,
                transparencyPercent = 100,
            ),
            settings.current(),
        )
    }

    @Test
    fun aPresetAndTheAreasSwitchPersist() {
        val file = Files.createTempFile("raster", ".json").toFile()
        val settings = RasterLayerSettings(file)

        assertEquals(Outcome.Success(Unit), settings.usePreset(RasterPresets.EOX_2018_ID))
        assertEquals(Outcome.Success(Unit), settings.setAreasShown(false))

        val reloaded = RasterLayerSettings(file).current()
        assertEquals(RasterPresets.eox2018, reloaded.source)
        assertFalse(reloaded.areasShown)
        file.delete()
    }

    @Test
    fun anUnknownPresetIsRefused() {
        val settings = RasterLayerSettings(file = null)

        assertEquals(Outcome.Failure(AppError.InvalidInput), settings.usePreset("nope"))
    }

    @Test
    fun anInvalidTemplateNeverReachesTheProbe() =
        runTest {
            var probes = 0
            val settings = RasterLayerSettings(file = null)

            val outcome =
                settings.useCustom("https://t.example/plain.png") { _ ->
                    probes += 1
                    Outcome.Success(Unit)
                }

            assertEquals(Outcome.Failure(AppError.InvalidInput), outcome)
            assertEquals(0, probes)
            assertEquals(RasterPresets.openStreetMap, settings.current().source)
        }

    @Test
    fun aProbedCustomSourceBecomesActiveAndPersists() =
        runTest {
            val file = temporaryFile()
            val settings = RasterLayerSettings(file = file)
            val template = "https://t.example/{z}/{x}/{y}.png"

            val outcome = settings.useCustom(template) { source -> probed(source, template) }

            assertTrue(outcome is Outcome.Success)
            assertEquals(template, settings.current().source.tileUrlTemplate)
            assertTrue(file.readText(), file.readText().contains(template))
            assertEquals(template, RasterLayerSettings(file = file).current().source.tileUrlTemplate)
        }

    @Test
    fun aFailedProbeKeepsThePreviousSourceAndWritesNothing() =
        runTest {
            val file = temporaryFile()
            file.delete()
            val settings = RasterLayerSettings(file = file)

            val outcome =
                settings.useCustom("https://t.example/{z}/{x}/{y}.png") {
                    Outcome.Failure(AppError.Network)
                }

            assertEquals(Outcome.Failure(AppError.Network), outcome)
            assertEquals(RasterPresets.openStreetMap, settings.current().source)
            assertFalse(file.exists())
        }

    @Test
    fun aCorruptFileFallsBackToTheDefaultLayer() {
        val file = temporaryFile()
        file.writeText("not json")

        assertEquals(
            RasterLayerState(
                source = RasterPresets.openStreetMap,
                placement = RasterPlacement.Overlay,
                transparencyPercent = 100,
            ),
            RasterLayerSettings(file = file).current(),
        )
    }

    @Test
    fun aPlacementAndATransparencyPersistAndReload() {
        val file = temporaryFile()
        val settings = RasterLayerSettings(file = file)

        assertEquals(Outcome.Success(Unit), settings.setPlacement(RasterPlacement.Underlay))
        assertEquals(Outcome.Success(Unit), settings.setTransparencyPercent(40))

        val reloaded = RasterLayerSettings(file = file).current()
        assertEquals(RasterPlacement.Underlay, reloaded.placement)
        assertEquals(40, reloaded.transparencyPercent)
    }

    @Test
    fun aPreviewTransparencyMovesTheMapWithoutWritingTheFile() {
        val file = temporaryFile()
        val settings = RasterLayerSettings(file = file)
        settings.setTransparencyPercent(40)
        val stored = file.readText()

        settings.setTransparencyPercent(55, store = false)

        assertEquals(55, settings.current().transparencyPercent)
        assertEquals(stored, file.readText())
        assertEquals(40, RasterLayerSettings(file = file).current().transparencyPercent)
    }

    @Test
    fun aFileFromBeforeThePlacementAndTransparencyExistsReadsTheirDefaults() {
        val file = temporaryFile()
        file.writeText("""{"source":"osm"}""")

        val layer = RasterLayerSettings(file = file).current()

        assertEquals(RasterPlacement.Overlay, layer.placement)
        assertEquals(100, layer.transparencyPercent)
    }

    @Test
    fun aTransparencyOutsideTheRangeIsClamped() {
        val file = temporaryFile()
        val settings = RasterLayerSettings(file = file)

        settings.setTransparencyPercent(140)
        assertEquals(100, settings.current().transparencyPercent)

        settings.setTransparencyPercent(-20)
        assertEquals(0, settings.current().transparencyPercent)
        assertEquals(0, RasterLayerSettings(file = file).current().transparencyPercent)
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
