package nl.r1a.grassmaps.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round-4 P1 4 follow-up: MapScreen must give the Saved sheet a real camera
 * action, not the shell's old no-op default. A composable cannot render on the
 * JVM, so the call chain is pinned from source; the shape matches the proven
 * search-hit camera action in the same file.
 */
class SavedMapWiringTest {
    private val mapScreen = File(MAP_SCREEN).readText()

    @Test
    fun theSavedSheetGetsACameraActionForTheSelectedPoint() {
        val call =
            mapScreen
                .substringAfter("FavouritesListsHost(")
                .substringBefore("modifier = modifier,")
        assertTrue("the Saved sheet has no map callback", call.contains("onShowOnMap = { point ->"))
        assertTrue("the map callback does not launch", call.contains("scope.launch"))
        assertTrue("the map callback does not move the camera", call.contains("CameraUpdate(center = point)"))
        assertTrue("the camera failure is not logged", call.contains("onFailure"))

        val helper =
            mapScreen
                .substringAfter("private fun MapSavedListsHost(")
                .substringBefore(") {")
        assertTrue("the shared helper cannot reach the map surface", helper.contains("surface: MapSurface"))
    }

    @Test
    fun theSavedScreenCallSitePassesTheMapSurface() {
        val callSites =
            Regex("MapSavedListsHost\\(")
                .findAll(mapScreen)
                .map { match -> mapScreen.substring(match.range.first, match.range.first + CALL_WINDOW) }
                .filter { window -> window.contains("container = container") }
                .toList()
        assertEquals("the Saved screen call site must pass the surface", 1, callSites.size)
        callSites.forEach { call ->
            assertTrue("a Saved sheet call site does not pass the surface", call.contains("surface = surface"))
        }
    }

    @Test
    fun theChevronSheetTilesMoveTheCamera() {
        val tile = mapScreen.substringAfter("val openSavedTile:").substringBefore("\n    }\n")
        assertTrue("a track tile does not open the track card", tile.contains("openTrack(target.id)"))
        assertTrue("a tile does not move the camera", tile.contains("CameraUpdate(center = point)"))
        assertTrue("the camera failure is not logged", tile.contains("onFailure"))
        val sheet = mapScreen.substringAfter("if (widgetsSavedSheetOpen && !navigationActive) {")
        assertTrue("the sheet does not route tile taps", sheet.contains("openSavedTile(tile.target)"))
    }

    private companion object {
        const val MAP_SCREEN = "src/main/java/nl/r1a/grassmaps/app/MapScreen.kt"
        const val CALL_WINDOW = 240
    }
}
