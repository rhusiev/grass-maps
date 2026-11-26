package nl.r1a.grassmaps.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 9 reviews (T-0612): every route press lives in the planner sheet's
 * pinned bar, and the top card is read-only. Save and Start keep the
 * planner's own commands, and every bar press changes the sheet view through
 * one callback. The JVM suite cannot render MapScreen, so the wiring is
 * pinned here; a rendered assertion would be stronger.
 */
class RouteActionHandoffTest {
    @Test
    fun thePlannerOwnsTheOnlySaveAndStartPaths() {
        val map = mapScreen()

        assertEquals("one Start path", 1, Regex("onStart = planStart").findAll(map).count())
        assertEquals("one Save path", 1, Regex("onSave = planSave").findAll(map).count())
        val card = map.substringAfter("RouteSummaryCardContent(").substringBefore("\n            )")
        assertFalse("the top card is read-only", card.contains("onStart") || card.contains("onSave"))
    }

    @Test
    fun everyBarPressChangesTheViewThroughOneCallback() {
        val map = mapScreen()

        assertTrue(map.contains("onViewChange = { routeSheetView = it }"))
        assertTrue(map.contains("view = routeSheetView"))
        assertTrue("a cleared plan returns to the preview", map.contains("routeSheetView = RoutePlanSheetView.Summary"))
    }

    private fun mapScreen(): String = File("src/main/java/nl/r1a/grassmaps/app/MapScreen.kt").readText()
}
