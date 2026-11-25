package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmaps.ui.shell.RoutePlanSheetView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round-4 design review P1 1 (T-0612, reopened). The card's Details and Stops
 * actions must open their own sections of the existing planner sheet, and Save
 * and Start must keep using the planner's own commands. The JVM suite cannot
 * render MapScreen, so the sheet visibility model and the lambda wiring are
 * pinned here; a rendered assertion would be stronger.
 */
class RouteActionHandoffTest {
    @Test
    fun bothHandoffsCloseTheReadOnlyCardAndOpenTheSheet() {
        for (view in listOf(RoutePlanSheetView.Stops, RoutePlanSheetView.Details)) {
            assertTrue("$view opens the planner sheet", routePlanSheetShown(RoutePlanStatus.Ready, view))
            assertFalse("$view hides the read-only card", routeSummaryCardShown(RoutePlanStatus.Ready, view))
        }
    }

    @Test
    fun theReadOnlyCardStaysTheDefaultReadyView() {
        assertTrue(routeSummaryCardShown(RoutePlanStatus.Ready, RoutePlanSheetView.Summary))
        assertFalse(routePlanSheetShown(RoutePlanStatus.Ready, RoutePlanSheetView.Summary))
    }

    @Test
    fun theCardReusesThePlannersSaveAndStartCommands() {
        val map = mapScreen()
        val count = Regex("onStart = planStart").findAll(map).count()
        val saves = Regex("onSave = planSave").findAll(map).count()

        assertEquals("the card and the planner share one Start path", 2, count)
        assertEquals("the card and the planner share one Save path", 2, saves)
    }

    @Test
    fun eachCardActionOpensOnlyItsOwnSheetSection() {
        val map = mapScreen()

        assertTrue(map.contains("onStops = { routeSheetView = RoutePlanSheetView.Stops }"))
        assertTrue(map.contains("onDetails = { routeSheetView = RoutePlanSheetView.Details }"))
        assertTrue(map.contains("view = routeSheetView"))
        assertTrue("the editable header is never the default", map.contains("routeSheetView = RoutePlanSheetView.Summary"))
    }

    private fun mapScreen(): String = File("src/main/java/nl/r1a/grassmaps/app/MapScreen.kt").readText()
}
