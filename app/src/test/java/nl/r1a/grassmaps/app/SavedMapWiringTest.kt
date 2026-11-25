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
                .substringBefore("embedded = embedded,")
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
    fun bothSavedSheetCallSitesPassTheMapSurface() {
        val callSites =
            Regex("MapSavedListsHost\\(")
                .findAll(mapScreen)
                .map { match -> mapScreen.substring(match.range.first, match.range.first + CALL_WINDOW) }
                .filter { window -> window.contains("container = container") }
                .toList()
        assertEquals("both Saved sheet call sites must pass the surface", 2, callSites.size)
        callSites.forEach { call ->
            assertTrue("a Saved sheet call site does not pass the surface", call.contains("surface = surface"))
        }
    }

    private companion object {
        const val MAP_SCREEN = "src/main/java/nl/r1a/grassmaps/app/MapScreen.kt"
        const val CALL_WINDOW = 240
    }
}
