package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmaps.ui.shell.RoutePlanSheetView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A ready route opens read-only (T-0612): the card over the map is the route
 * view, and the planner sheet opens only through the card's Stops or Details
 * action. The picking, planning and failed states keep the sheet, because it
 * carries their hints and recovery actions.
 */
class RoutePlannerDefaultViewTest {
    @Test
    fun aReadyRouteDoesNotOpenThePlannerSheetOnItsOwn() {
        assertFalse(
            "a ready route must show the read-only card, not the planner sheet",
            routePlanSheetShown(RoutePlanStatus.Ready, RoutePlanSheetView.Summary),
        )
    }

    @Test
    fun aReadyRouteShowsTheReadOnlyCardUntilAnActionOpensTheSheet() {
        assertTrue(routeSummaryCardShown(RoutePlanStatus.Ready, RoutePlanSheetView.Summary))
        assertFalse(
            "the sheet takes over while Stops is open",
            routeSummaryCardShown(RoutePlanStatus.Ready, RoutePlanSheetView.Stops),
        )
        assertFalse(
            "the sheet takes over while Details is open",
            routeSummaryCardShown(RoutePlanStatus.Ready, RoutePlanSheetView.Details),
        )
    }

    @Test
    fun bothCardActionsOpenTheSheetOnAReadyRoute() {
        assertTrue(routePlanSheetShown(RoutePlanStatus.Ready, RoutePlanSheetView.Stops))
        assertTrue(routePlanSheetShown(RoutePlanStatus.Ready, RoutePlanSheetView.Details))
    }

    @Test
    fun thePlanningStatesKeepTheSheet() {
        val planning =
            listOf(
                RoutePlanStatus.PickingStart,
                RoutePlanStatus.PickingDestination,
                RoutePlanStatus.Planning,
                RoutePlanStatus.Failed,
            )
        for (status in planning) {
            assertTrue("$status needs the sheet's hints and actions", routePlanSheetShown(status, RoutePlanSheetView.Summary))
        }
    }

    @Test
    fun anIdlePlanComposesNoSheet() {
        assertFalse(routePlanSheetShown(RoutePlanStatus.Idle, RoutePlanSheetView.Summary))
        assertFalse(routePlanSheetShown(RoutePlanStatus.Idle, RoutePlanSheetView.Stops))
        assertFalse(routePlanSheetShown(RoutePlanStatus.Idle, RoutePlanSheetView.Details))
    }
}
