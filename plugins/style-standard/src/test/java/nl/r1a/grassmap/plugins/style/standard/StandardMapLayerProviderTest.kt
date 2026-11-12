package nl.r1a.grassmap.plugins.style.standard

import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerType
import nl.r1a.grassmap.plugin.api.model.MapSourceIds
import nl.r1a.grassmap.plugin.api.model.StyleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardMapLayerProviderTest {
    private val provider = StandardMapLayerProvider()
    private val light = StyleId("standard")
    private val dark = StyleId("standard-dark")
    private val touringLight = StyleId("touring")
    private val touringDark = StyleId("touring-dark")

    @Test
    fun fourVariantsShareOneBaseDocument() {
        val styles = provider.styles()

        assertEquals(listOf(light, dark, touringLight, touringDark), styles.map { it.id })
        assertEquals(styles[0].baseStyleJson, styles[1].baseStyleJson)
        assertEquals(StandardStyle.sources, styles[0].sources)
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
        for (styleId in listOf(light, dark, touringLight, touringDark)) {
            val overlays = provider.layers(styleId).filter { it.slot == LayerSlot.Overlays }

            assertTrue("$styleId: contours are the contour plugin's layers", overlays.isEmpty())
        }
    }

    @Test
    fun touringStartsEachLayerOneZoomEarlier() {
        val standardLayer =
            provider.layers(light).first {
                it.spec.id.value
                    .endsWith("__region") && it.spec.minZoom == 13f
            }
        val touringLayer = provider.layers(touringLight).first { it.spec.id == standardLayer.spec.id }

        assertEquals(12f, touringLayer.spec.minZoom)
        assertEquals(standardLayer.spec.maxZoom, touringLayer.spec.maxZoom)
    }

    @Test
    fun touringLightAndDarkShareLayerIdsButSwapPaletteColours() {
        val lightLayers = provider.layers(touringLight)
        val darkLayers = provider.layers(touringDark)

        assertEquals(lightLayers.map { it.spec.id }, darkLayers.map { it.spec.id })
        assertNotEquals(
            lightLayers.map { it.spec.paint },
            darkLayers.map { it.spec.paint },
        )
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
            listOf(light, dark, touringLight, touringDark).flatMap { styleId ->
                provider.layers(styleId).map { it.spec.paint.toString() }
            }

        assertTrue(paints.none { it.contains("\"@") })
    }
}
