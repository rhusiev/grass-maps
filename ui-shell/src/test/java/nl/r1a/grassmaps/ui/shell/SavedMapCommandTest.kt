package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.FavouriteBrowseState
import nl.r1a.grassmaps.plugin.api.model.FavouriteListsState
import nl.r1a.grassmaps.plugin.api.model.FavouritePointRow
import nl.r1a.grassmaps.plugin.api.model.FavouritePointSummary
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round-4 P1 4 follow-up: the Saved Map action was enabled but its callback
 * defaulted to a no-op and the app never supplied a camera action. The callback
 * must carry the selected point's coordinates and the host must resolve them
 * from the favourites state before the bar can enable the button.
 */
class SavedMapCommandTest {
    private val host = File(HOST_FILE).readText()

    @Test
    fun theMapCallbackCarriesTheSelectedPointInsteadOfANoOpDefault() {
        assertFalse(
            "the Saved map callback is still a no-op default",
            host.contains("onShowOnMap: () -> Unit = {}"),
        )
        assertTrue(
            "the map callback does not carry coordinates",
            host.contains("onShowOnMap: (GeoPoint) -> Unit"),
        )
        assertTrue(
            "the host does not resolve the selected point",
            host.contains("selectedPlacePosition(state, selectedPoint)"),
        )
        assertTrue(
            "the button can be enabled with no coordinate to show",
            host.contains("placeSelected = selectedPosition != null"),
        )
        assertTrue("the persisted visibility read was lost", host.contains("hiddenGroups = hiddenGroups"))
    }

    @Test
    fun theSelectedPlaceSuppliesCoordinatesToTheMapCallback() {
        val point = GeoPoint(49.555791, 25.592375)
        val state =
            FavouriteListsState(
                points = listOf(FavouritePointSummary("one", "Flat", position = point)),
                browse =
                    FavouriteBrowseState(
                        rows = listOf(FavouritePointRow(id = "one", name = "Flat", position = point)),
                    ),
            )

        assertEquals(point, selectedPlacePosition(state, "one"))
        assertNull(selectedPlacePosition(state, null))
        assertNull(selectedPlacePosition(state, "missing"))
        assertNull(selectedPlacePosition(FavouriteListsState(), "one"))
    }

    @Test
    fun aFilteredOutPointStillResolvesFromTheFullLibrary() {
        val point = GeoPoint(49.84, 24.02)
        val state =
            FavouriteListsState(
                points = listOf(FavouritePointSummary("two", "Square", position = point)),
                browse = FavouriteBrowseState(rows = emptyList()),
            )

        assertEquals(point, selectedPlacePosition(state, "two"))
    }

    private companion object {
        const val HOST_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/FavouritesListsHost.kt"
    }
}
