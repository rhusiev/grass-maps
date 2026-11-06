package nl.r1a.grassmap.plugins.style.standard

import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.MapSourceIds
import nl.r1a.grassmap.plugin.api.model.StyleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardMapLayerProviderTest {
    private val provider = StandardMapLayerProvider()

    @Test
    fun bothVariantsShareOneBaseDocument() {
        val styles = provider.styles()

        assertEquals(listOf(StyleId("standard"), StyleId("touring")), styles.map { it.id })
        assertEquals(styles[0].baseStyleJson, styles[1].baseStyleJson)
        assertEquals(BenchStyle.sources, styles[0].sources)
    }

    @Test
    fun layersAreSplitAcrossWorldAndRegion() {
        val layers = provider.layers(StyleId("standard"))

        val base =
            layers.filter { it.slot == LayerSlot.Base && it.spec.type.name != "Background" }
        val world = base.filter { it.spec.source == MapSourceIds.WORLD }
        val region = base.filter { it.spec.source == MapSourceIds.REGION }

        assertTrue(world.isNotEmpty())
        assertTrue(region.isNotEmpty())
        assertTrue(world.all { it.spec.minZoom < 8f && it.spec.maxZoom <= 8f })
        assertTrue(region.all { it.spec.minZoom >= 8f })
    }

    @Test
    fun contoursAreAnOverlayOnTheContoursSource() {
        val contours =
            provider.layers(StyleId("standard")).filter { it.slot == LayerSlot.Overlays }

        assertTrue(contours.isNotEmpty())
        assertTrue(contours.all { it.spec.source == MapSourceIds.CONTOURS })
    }

    @Test
    fun touringChangesTheLayersButNotTheBaseStyle() {
        val standard = provider.layers(StyleId("standard"))
        val touring = provider.layers(StyleId("touring"))

        assertEquals(standard.map { it.spec.id }, touring.map { it.spec.id })
        assertNotEquals(standard.map { it.spec.paint }, touring.map { it.spec.paint })
    }
}
