package nl.r1a.grassmap.plugins.style.standard

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerType
import nl.r1a.grassmap.plugin.api.model.MapSourceIds
import nl.r1a.grassmap.plugin.api.model.StyleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardMapLayerProviderTest {
    private val provider = StandardMapLayerProvider()
    private val light = StyleId("standard")
    private val dark = StyleId("standard-dark")

    @Test
    fun standardHasLightAndDarkVariantsOverOneBaseDocument() {
        val styles = provider.styles()

        assertEquals(listOf(light, dark), styles.map { it.id })
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
    fun contoursAreAnOverlayOnTheContoursSource() {
        for (styleId in listOf(light, dark)) {
            val contours = provider.layers(styleId).filter { it.slot == LayerSlot.Overlays }

            assertTrue(contours.isNotEmpty())
            assertTrue(contours.all { it.spec.source == MapSourceIds.CONTOURS })
        }
    }

    @Test
    fun contourFiltersUseTheArchivesBooleanIndex() {
        val contours = provider.layers(light).filter { it.slot == LayerSlot.Overlays }
        val filters = contours.associate { it.spec.id.value to it.spec.filter }

        assertEquals(
            JsonArray(listOf(JsonPrimitive("=="), JsonPrimitive("idx"), JsonPrimitive(true))),
            filters["contour-index"],
        )
        assertEquals(
            JsonArray(listOf(JsonPrimitive("=="), JsonPrimitive("idx"), JsonPrimitive(false))),
            filters["contour-20"],
        )
        assertEquals(JsonArray(listOf(JsonPrimitive("=="), JsonPrimitive("idx"), JsonPrimitive(false))), filters["contour-10"])
        for ((id, filter) in filters) {
            val value = requireNotNull(filter).jsonArray[2].jsonPrimitive
            assertNotNull("$id must compare a JSON boolean, not a number", value.booleanOrNull)
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

    @Test
    fun darkContoursUseTheDarkPalette() {
        val lightContour = provider.layers(light).first { it.spec.id.value == "contour-20" }
        val darkContour = provider.layers(dark).first { it.spec.id.value == "contour-20" }

        assertNotEquals(lightContour.spec.paint, darkContour.spec.paint)
    }
}
