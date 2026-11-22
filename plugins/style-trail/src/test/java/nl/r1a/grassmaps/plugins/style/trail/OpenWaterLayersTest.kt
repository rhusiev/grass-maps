package nl.r1a.grassmaps.plugins.style.trail

import kotlinx.serialization.json.JsonElement
import nl.r1a.grassmaps.plugin.api.model.StyleId
import nl.r1a.grassmaps.plugin.api.testing.fixture.StyleFilters
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Trail style draws the same water source layer as Meadow, so it needs
 * the same culvert exclusion: the Poltva is not open water under Svobody
 * Avenue, and the pack keeps its `layer=-3` even though it drops the culvert
 * tag.
 */
class OpenWaterLayersTest {
    private val provider = TrailMapLayerProvider()
    private val style = StyleId("trail")

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
