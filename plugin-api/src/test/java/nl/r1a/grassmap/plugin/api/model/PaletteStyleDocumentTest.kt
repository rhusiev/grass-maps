package nl.r1a.grassmap.plugin.api.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteStyleDocumentTest {
    private val document =
        PaletteStyleDocument.parse(
            """
            {
              "version": 8,
              "styles": {"a": "p1", "b": "p2", "orphan": "missing"},
              "palettes": {"p1": {"fill": "#111111"}, "p2": {"fill": "#222222"}},
              "zoomOffsets": {"b": -1},
              "layers": [
                {"id": "land", "type": "fill", "source-layer": "land", "minzoom": 5, "maxzoom": 12,
                 "paint": {"fill-color": "@fill"}}
              ]
            }
            """.trimIndent(),
        )

    @Test
    fun variantsSkipAStyleWhosePaletteIsMissing() {
        assertEquals(listOf(StyleId("a"), StyleId("b")), document.variants())
    }

    @Test
    fun variantsShareOneBaseDocumentWithoutLayers() {
        assertTrue(document.baseStyle().contains("\"layers\":[]"))
    }

    @Test
    fun aLayerSpanningTheSeamSplitsIntoWorldAndRegion() {
        val layers = document.layersFor(StyleId("a"))

        assertEquals(listOf("land__world", "land__region"), layers.map { it.spec.id.value })
        assertEquals(8f, layers[0].spec.maxZoom)
        assertEquals(8f, layers[1].spec.minZoom)
    }

    @Test
    fun aZoomOffsetShiftsBothEndsOfTheLayer() {
        val layers = document.layersFor(StyleId("b"))

        assertEquals(4f, layers.first().spec.minZoom)
        assertEquals(11f, layers.last().spec.maxZoom)
    }

    @Test
    fun anUnknownStyleHasNoLayers() {
        assertTrue(document.layersFor(StyleId("nope")).isEmpty())
    }
}
