package nl.r1a.grassmap.plugins.style.touring

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerType
import nl.r1a.grassmap.plugin.api.model.MapSourceIds
import nl.r1a.grassmap.plugin.api.model.PaletteStyleDocument
import nl.r1a.grassmap.plugin.api.model.StyleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TouringMapLayerProviderTest {
    private val provider = TouringMapLayerProvider()
    private val light = StyleId("touring")
    private val dark = StyleId("touring-dark")
    private val standardFile = File("../style-standard/src/main/styles/standard.json")
    private val standard = PaletteStyleDocument.parse(standardFile.readText())

    @Test
    fun lightAndDarkShareOneBaseDocument() {
        val styles = provider.styles()

        assertEquals(listOf(light, dark), styles.map { it.id })
        assertEquals(styles[0].baseStyleJson, styles[1].baseStyleJson)
        assertEquals(PaletteStyleDocument.sources, styles[0].sources)
    }

    @Test
    fun baseDocumentEqualsStandardSoSwitchingNeedsNoReload() {
        assertEquals(standard.baseStyle(), provider.styles().first().baseStyleJson)
    }

    @Test
    fun layersAreSplitAcrossWorldAndRegion() {
        val base =
            provider
                .layers(light)
                .filter { it.slot == LayerSlot.Base && it.spec.type != LayerType.Background }
        val world = base.filter { it.spec.source == MapSourceIds.WORLD }
        val region = base.filter { it.spec.source == MapSourceIds.REGION }

        assertTrue(world.isNotEmpty())
        assertTrue(region.isNotEmpty())
        assertTrue(world.all { it.spec.minZoom < 8f && it.spec.maxZoom <= 8f })
        assertTrue(region.all { it.spec.minZoom >= 8f })
    }

    @Test
    fun theBaseStyleAddsNoOverlayLayers() {
        for (styleId in listOf(light, dark)) {
            val overlays = provider.layers(styleId).filter { it.slot == LayerSlot.Overlays }

            assertTrue("$styleId: contours are the contour plugin's layers", overlays.isEmpty())
        }
    }

    @Test
    fun touringStartsEachLayerOneZoomEarlierThanStandard() {
        val standardLayer =
            standard.layersFor(StyleId("standard")).first {
                it.spec.id.value
                    .endsWith("__region") && it.spec.minZoom == 13f
            }
        val touringLayer = provider.layers(light).first { it.spec.id == standardLayer.spec.id }

        assertEquals(12f, touringLayer.spec.minZoom)
        assertEquals(standardLayer.spec.maxZoom, touringLayer.spec.maxZoom)
    }

    @Test
    fun lightAndDarkShareLayerIdsButSwapPaletteColours() {
        val lightLayers = provider.layers(light)
        val darkLayers = provider.layers(dark)

        assertEquals(lightLayers.map { it.spec.id }, darkLayers.map { it.spec.id })
        assertNotEquals(lightLayers.map { it.spec.paint }, darkLayers.map { it.spec.paint })
    }

    @Test
    fun everyColourTokenIsResolved() {
        val paints =
            listOf(light, dark).flatMap { styleId ->
                provider.layers(styleId).map { it.spec.paint.toString() }
            }

        assertTrue(paints.none { it.contains("\"@") })
    }

    @Test
    fun pathKindsFormADashLadderFromTwelve() {
        val ladder = listOf("roads_footway", "roads_path", "roads_steps", "roads_track", "roads_cycleway")
        val specs =
            ladder.map { id ->
                provider.layers(light).single { it.spec.id.value == id + "__region" }.spec
            }

        assertTrue(specs.all { it.minZoom == 12f })
        val patterns = specs.map { it.paint["line-dasharray"].toString() }
        assertEquals(patterns.take(4).distinct().size, 4)
    }

    @Test
    fun symbolLayersMatchStandardSoTheSpriteStillCoversThem() {
        val standardSymbols = authoredSymbols(standardFile)
        val touringSymbols = authoredSymbols(File("src/main/styles/touring.json"))

        assertEquals(standardSymbols, touringSymbols)
    }

    private fun authoredSymbols(file: File): List<JsonObject> =
        Json
            .parseToJsonElement(file.readText())
            .jsonObject
            .getValue("layers")
            .jsonArray
            .map { it.jsonObject }
            .filter { it.getValue("type").jsonPrimitive.content == "symbol" }
}
