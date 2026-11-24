package nl.r1a.grassmaps.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MapLayersSheetWiringTest {
    private val source = File("src/main/java/nl/r1a/grassmaps/app/MapScreen.kt").readText()

    @Test
    fun layersSheetComposesAboveTheControlRailDevPanelAndSearch() {
        val sheet = source.indexOf("LayersSheet(")

        for (underlay in listOf("MapControlBar(", "devToolsPanel(", "SearchOverlay(")) {
            val index = source.indexOf(underlay)
            assertTrue("$underlay must be composed before LayersSheet", index in 0 until sheet)
        }
    }

    @Test
    fun layersSheetUsesVisibleControlsForStyleContoursAndSavedPlaces() {
        val layers = source.substringAfter("LayersSheet(").substringBefore("RasterLayerPanel(")

        assertTrue(layers.contains("LayerStyleChoices("))
        assertTrue(layers.contains("LayerMarkerShapeOptions("))
        for (label in listOf("map_contours_toggle", "favourites_layer_icons", "favourites_layer_names")) {
            assertTrue("Missing switch for $label", Regex("LayerToggle\\(\\s*label = .*\\.$label\\)").containsMatchIn(layers))
        }
    }
}
