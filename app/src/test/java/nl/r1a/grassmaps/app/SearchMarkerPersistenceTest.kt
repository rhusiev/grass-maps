package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.provider.PlaceHit
import nl.r1a.grassmaps.plugin.api.provider.PlaceSource
import nl.r1a.grassmaps.ui.shell.searchResultView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Train 101 follow-up: collapsing the expanded search card took the result
 * markers off the map, because `MapScreen` composed `SearchOverlay` only while
 * `searchExpanded` was true. The overlay must stay composed after the card
 * closes, drawing only its markers, until the results are cleared or replaced.
 *
 * `SearchOverlay` is a private Composable, so a JVM test cannot render it. The
 * first two tests pin the source shape that caused the regression; the clear
 * test pins the pure derivation that feeds the markers.
 */
class SearchMarkerPersistenceTest {
    private val source = File(MAP_SCREEN_FILE).readText()

    @Test
    fun markersSurviveCollapseAndClearRemovesThem() {
        assertFalse(
            "the overlay is still gated on searchExpanded, so its markers vanish when the card closes",
            source.contains("if (!navigationActive && searchExpanded) {"),
        )
        val hits = listOf(hit("lviv", "Площа Ринок", 49.8413, 24.0312))
        assertEquals(listOf("lviv"), searchResultView(hits).markers.map { it.id })
        assertTrue(
            "a cleared result set must leave no markers",
            searchResultView(emptyList()).markers.isEmpty(),
        )
    }

    @Test
    fun collapsingTheCardSwitchesToMarkersOnlyInsteadOfHidingTheOverlay() {
        assertEquals(
            "the collapsed card must still compose the markers",
            SearchOverlayMode.MARKERS_ONLY,
            searchOverlayMode(navigationActive = false, searchExpanded = false),
        )
        assertEquals(
            SearchOverlayMode.EXPANDED,
            searchOverlayMode(navigationActive = false, searchExpanded = true),
        )
    }

    @Test
    fun navigationHidesTheOverlayAndItsMarkers() {
        assertEquals(
            SearchOverlayMode.HIDDEN,
            searchOverlayMode(navigationActive = true, searchExpanded = true),
        )
        assertEquals(
            SearchOverlayMode.HIDDEN,
            searchOverlayMode(navigationActive = true, searchExpanded = false),
        )
    }

    @Test
    fun theCollapsedOverlayDrawsOnlyMarkersAndNoSecondSearchPill() {
        assertTrue(
            "the host still draws the old collapsed search pill",
            source.contains("showCollapsedField = false"),
        )
        assertTrue(
            "the single bottom bar must keep its own Search action",
            source.contains("onOpenSearch = { searchExpanded = true }"),
        )
        assertTrue(
            "the widgets and Saved sheet must stay in the single bar",
            source.contains("WidgetsSavedSheet("),
        )
    }

    private fun hit(
        id: String,
        name: String,
        latitude: Double,
        longitude: Double,
        source: PlaceSource = PlaceSource.OFFLINE,
    ): PlaceHit =
        PlaceHit(
            id = id,
            name = name,
            kind = "place=square",
            position = GeoPoint(latitude, longitude),
            address = "Львів",
            regionBuild = "test",
            score = 1f,
            brand = null,
            source = source,
        )

    private companion object {
        const val MAP_SCREEN_FILE = "src/main/java/nl/r1a/grassmaps/app/MapScreen.kt"
    }
}
