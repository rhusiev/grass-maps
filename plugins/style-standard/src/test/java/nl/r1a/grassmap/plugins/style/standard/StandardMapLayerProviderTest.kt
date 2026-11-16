package nl.r1a.grassmap.plugins.style.standard

import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerType
import nl.r1a.grassmap.plugin.api.model.MapSourceIds
import nl.r1a.grassmap.plugin.api.model.PaletteStyleDocument
import nl.r1a.grassmap.plugin.api.model.StyleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardMapLayerProviderTest {
    private val provider = StandardMapLayerProvider()
    private val light = StyleId("standard")
    private val dark = StyleId("standard-dark")

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
    fun standardOffersOnlyItsTwoVariants() {
        assertEquals(listOf(light, dark), provider.styles().map { it.id })
        assertTrue(provider.layers(StyleId("touring")).isEmpty())
    }

    @Test
    fun standardKeepsTheAuthoredZoomRange() {
        val layer =
            provider.layers(light).first {
                it.spec.id.value
                    .endsWith("__region") && it.spec.minZoom == 13f
            }

        assertEquals(13f, layer.spec.minZoom)
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
}
