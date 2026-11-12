package nl.r1a.grassmap.plugins.contours

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerType
import nl.r1a.grassmap.plugin.api.model.MapSourceIds
import nl.r1a.grassmap.plugin.api.model.SlottedLayer
import nl.r1a.grassmap.plugin.api.model.StyleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContourLayerTest {
    @Test
    fun everyVariantDrawsThreeBandsAnIndexAndOneLabel() {
        val ids = ContourVariant.entries.map { it.styleId }
        for (id in ids) {
            val layers = ContourLayers.layers(id)
            assertEquals("$id layer count", 5, layers.size)
            assertEquals(
                listOf("contour-50", "contour-20", "contour-10", "contour-index", "contour-label"),
                layers.map { it.spec.id.value },
            )
            assertTrue("$id overlays", layers.all { it.slot == LayerSlot.Overlays })
            assertTrue("$id source", layers.all { it.spec.source == MapSourceIds.CONTOURS })
            assertTrue("$id source layer", layers.all { it.spec.sourceLayer == "contours" })
        }
    }

    @Test
    fun standardShowsFiftyTwentyAndTenAtZ12To14() {
        val layers = ContourLayers.layers(StyleId("standard"))

        assertEquals(12f to 13f, zoomOf(layers, "contour-50"))
        assertEquals(13f to 14f, zoomOf(layers, "contour-20"))
        assertEquals(14f to 24f, zoomOf(layers, "contour-10"))
        assertEquals(12f to 24f, zoomOf(layers, "contour-index"))
        assertEquals(14f to 24f, zoomOf(layers, "contour-label"))
    }

    @Test
    fun touringShowsEveryBandOneZoomEarlier() {
        val layers = ContourLayers.layers(StyleId("touring"))

        assertEquals(11f to 12f, zoomOf(layers, "contour-50"))
        assertEquals(12f to 13f, zoomOf(layers, "contour-20"))
        assertEquals(13f to 24f, zoomOf(layers, "contour-10"))
        assertEquals(11f to 24f, zoomOf(layers, "contour-index"))
        assertEquals(13f to 24f, zoomOf(layers, "contour-label"))
    }

    @Test
    fun theDarkAndLightVariantsShareTheirZoomTable() {
        for ((light, dark) in listOf(
            StyleId("standard") to StyleId("standard-dark"),
            StyleId("touring") to StyleId("touring-dark"),
        )) {
            val lightZooms = ContourLayers.layers(light).map { it.spec.id.value to zoomOf(it) }
            val darkZooms = ContourLayers.layers(dark).map { it.spec.id.value to zoomOf(it) }
            assertEquals("$light and $dark", lightZooms, darkZooms)
        }
    }

    @Test
    fun thePaletteMatchesMapStyleSectionThree() {
        assertEquals("#dcc2a6", palette("standard").minor)
        assertEquals("#c9a27c", palette("standard").index)
        assertEquals("#2e343c", palette("standard-dark").minor)
        assertEquals("#3e4650", palette("standard-dark").index)
        assertEquals("#d6b48e", palette("touring").minor)
        assertEquals("#bf9364", palette("touring").index)
        assertEquals("#24403a", palette("touring-dark").minor)
        assertEquals("#30554c", palette("touring-dark").index)
    }

    @Test
    fun indexAndBandLayersUseTheAuthoredWidthsAndOpacity() {
        val layers = ContourLayers.layers(StyleId("standard"))

        assertEquals(1.3f, paintFloat(layers, "contour-index", "line-width"))
        assertEquals(0.85f, paintFloat(layers, "contour-index", "line-opacity"))
        assertEquals(1f, paintFloat(layers, "contour-50", "line-width"))
        assertEquals(0.7f, paintFloat(layers, "contour-20", "line-opacity"))
        assertEquals(0.8f, paintFloat(layers, "contour-10", "line-width"))
        assertEquals(0.55f, paintFloat(layers, "contour-10", "line-opacity"))
    }

    @Test
    fun theIndexFilterComparesTheBooleanAgainstTrue() {
        val index = ContourLayers.layers(StyleId("standard")).first { it.spec.id.value == "contour-index" }
        val filter = index.spec.filter!!.jsonArray

        assertEquals("==", (filter[0] as JsonPrimitive).content)
        assertEquals("get", ((filter[1] as JsonArray)[0] as JsonPrimitive).content)
        assertEquals("idx", ((filter[1] as JsonArray)[1] as JsonPrimitive).content)
        assertEquals("true", (filter[2] as JsonPrimitive).content)
    }

    @Test
    fun aBandAcceptsOnlyNonIndexLinesWhoseElevationDividesByItsInterval() {
        for (meters in listOf(50, 20, 10)) {
            val layer = ContourLayers.layers(StyleId("standard")).first { it.spec.id.value == "contour-$meters" }
            val parts = layer.spec.filter!!.jsonArray
            assertEquals("all", (parts[0] as JsonPrimitive).content)
            val notIndex = (parts[1] as JsonArray)
            assertEquals("!=", (notIndex[0] as JsonPrimitive).content)
            val modulo = (parts[2] as JsonArray)
            assertEquals("==", (modulo[0] as JsonPrimitive).content)
            val division = modulo[1] as JsonArray
            assertEquals("%", (division[0] as JsonPrimitive).content)
            assertEquals(meters.toString(), (division[2] as JsonPrimitive).content)
            assertEquals("0", (modulo[2] as JsonPrimitive).content)
        }
    }

    @Test
    fun theLabelIsALinePlacedSymbolWithTheAuthoredHalo() {
        val label = ContourLayers.layers(StyleId("standard-dark")).single { it.spec.type == LayerType.Symbol }

        assertEquals(10f, (label.spec.layout["text-size"] as JsonPrimitive).content.toFloat())
        assertEquals("Noto Sans Italic", (label.spec.layout["text-font"] as JsonArray)[0].toString().trim('"'))
        assertEquals("line", (label.spec.layout["symbol-placement"] as JsonPrimitive).content)
        assertEquals("source", (label.spec.layout["symbol-z-order"] as JsonPrimitive).content)
        assertEquals("#e3e6ea", (label.spec.paint["text-color"] as JsonPrimitive).content)
        assertEquals("#161c26", (label.spec.paint["text-halo-color"] as JsonPrimitive).content)
        assertTrue(label.spec.layout.containsKey("text-field"))
    }

    @Test
    fun anUnknownStyleDrawsNoLayers() {
        assertTrue(ContourLayers.layers(StyleId("standard-water")).isEmpty())
        assertNull(ContourVariant.forStyle(StyleId("standard-water")))
    }

    private fun palette(style: String): ContourPalette = ContourVariant.forStyle(StyleId(style))!!.palette

    private fun zoomOf(
        layers: List<SlottedLayer>,
        id: String,
    ): Pair<Float, Float> {
        val spec = layers.first { it.spec.id.value == id }.spec
        return spec.minZoom to spec.maxZoom
    }

    private fun zoomOf(layer: SlottedLayer): String = "${layer.spec.minZoom}-${layer.spec.maxZoom}"

    private fun paintFloat(
        layers: List<SlottedLayer>,
        id: String,
        key: String,
    ): Float = (layers.first { it.spec.id.value == id }.spec.paint[key] as JsonPrimitive).content.toFloat()
}
