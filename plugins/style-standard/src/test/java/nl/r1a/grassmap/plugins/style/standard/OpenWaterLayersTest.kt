package nl.r1a.grassmap.plugins.style.standard

import kotlinx.serialization.json.JsonElement
import nl.r1a.grassmap.plugin.api.model.StyleId
import nl.r1a.grassmap.plugin.api.testing.fixture.StyleFilters
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Poltva runs in a culvert under Svobody Avenue in Lviv. The pack keeps
 * the OSM `layer=-3` on the waterway but drops `tunnel=culvert`, so the style
 * is the last place that can keep it off the open-water layers.
 */
class OpenWaterLayersTest {
    private val provider = StandardMapLayerProvider()
    private val style = StyleId("standard")

    @Test
    fun aCulvertedWaterwayDoesNotReachTheOpenWaterLayers() {
        val river = filter("water_river__region")
        val stream = filter("water_stream__region")

        assertFalse(StyleFilters.matches(river, mapOf("kind" to "river", "layer" to -3)))
        assertFalse(StyleFilters.matches(stream, mapOf("kind" to "stream", "layer" to -1)))
        assertTrue(StyleFilters.matches(river, mapOf("kind" to "river")))
        assertTrue(StyleFilters.matches(river, mapOf("kind" to "river", "layer" to 0)))
    }

    @Test
    fun aWaterPolygonBelowTheSurfaceDoesNotReachTheWaterFill() {
        val fill = filter("water__region")

        assertFalse(StyleFilters.matches(fill, mapOf("kind" to "water", "layer" to -1)))
        assertFalse(StyleFilters.matches(fill, mapOf("kind" to "water", "tunnel" to "culvert")))
        assertTrue(StyleFilters.matches(fill, mapOf("kind" to "water")))
        assertTrue(StyleFilters.matches(fill, mapOf("kind" to "lake", "layer" to 0)))
    }

    private fun filter(id: String): JsonElement? =
        provider
            .layers(style)
            .single { it.spec.id.value == id }
            .spec.filter
}
