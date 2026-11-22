package nl.r1a.grassmaps.plugins.style.meadow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.r1a.grassmaps.plugin.api.model.LayerSlot
import nl.r1a.grassmaps.plugin.api.model.LayerType
import nl.r1a.grassmaps.plugin.api.model.MapCredits
import nl.r1a.grassmaps.plugin.api.model.MapSourceIds
import nl.r1a.grassmaps.plugin.api.model.PaletteStyleDocument
import nl.r1a.grassmaps.plugin.api.model.StyleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MeadowMapLayerProviderTest {
    private val provider = MeadowMapLayerProvider()
    private val light = StyleId("meadow")
    private val dark = StyleId("meadow-dark")

    @Test
    fun vectorSourcesDeclareTheirCredits() {
        for (style in provider.styles()) {
            val credits = style.sources.associate { it.id to it.attribution }

            assertEquals(MapCredits.OPENSTREETMAP, credits[MapSourceIds.WORLD])
            assertEquals(MapCredits.OPENSTREETMAP, credits[MapSourceIds.REGION])
            assertEquals(MapCredits.COPERNICUS_DEM, credits[MapSourceIds.CONTOURS])
        }
    }

    @Test
    fun lightAndDarkShareOneBaseDocument() {
        val styles = provider.styles()

        assertEquals(listOf(light, dark), styles.map { it.id })
        assertEquals(styles[0].baseStyleJson, styles[1].baseStyleJson)
        assertEquals(PaletteStyleDocument.sources, styles[0].sources)
    }

    @Test
    fun layersAreSplitAcrossWorldAndRegion() {
        val layers = provider.layers(light)

        val base =
            layers.filter { it.slot == LayerSlot.Base && it.spec.type != LayerType.Background }
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
    fun meadowOffersOnlyItsTwoVariants() {
        assertEquals(listOf(light, dark), provider.styles().map { it.id })
        assertTrue(provider.layers(StyleId("trail")).isEmpty())
    }

    @Test
    fun meadowKeepsTheAuthoredZoomRange() {
        val authored = authoredRanges()

        assertTrue(authored.isNotEmpty())
        for (styleId in listOf(light, dark)) {
            val layers = provider.layers(styleId)
            for ((id, range) in authored) {
                val pieces =
                    layers.filter {
                        it.spec.id.value
                            .removeSuffix(WORLD)
                            .removeSuffix(REGION) == id
                    }
                assertEquals("$styleId $id min", range.first, pieces.minOf { it.spec.minZoom })
                assertEquals("$styleId $id max", range.second, pieces.maxOf { it.spec.maxZoom })
            }
        }
    }

    @Test
    fun lightAndDarkShareLayerIdsButSwapPaletteColours() {
        val lightLayers = provider.layers(light)
        val darkLayers = provider.layers(dark)

        assertEquals(lightLayers.map { it.spec.id }, darkLayers.map { it.spec.id })
        assertNotEquals(
            lightLayers.map { it.spec.paint },
            darkLayers.map { it.spec.paint },
        )
    }

    @Test
    fun everyColourTokenIsResolved() {
        val paints =
            listOf(light, dark).flatMap { styleId ->
                provider.layers(styleId).map { it.spec.paint.toString() }
            }

        assertTrue(paints.none { it.contains("\"@") })
    }

    private fun authoredRanges(): Map<String, Pair<Float, Float>> =
        Json
            .parseToJsonElement(File("src/main/styles/meadow.json").readText())
            .jsonObject
            .getValue("layers")
            .jsonArray
            .map { it.jsonObject }
            .associate { layer ->
                layer.getValue("id").jsonPrimitive.content to
                    (
                        layer.getValue("minzoom").jsonPrimitive.float to
                            layer.getValue("maxzoom").jsonPrimitive.float
                    )
            }

    private companion object {
        const val WORLD = "__world"
        const val REGION = "__region"
    }
}
