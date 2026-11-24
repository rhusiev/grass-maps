package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.plugin.api.model.RoutePlanStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A ready route opens read-only (T-0612): the card over the map is the route
 * view, and the planner sheet opens only through the card's Stops action. The
 * picking, planning and failed states keep the sheet, because it carries their
 * hints and recovery actions.
 */
class RoutePlannerDefaultViewTest {
    @Test
    fun aReadyRouteDoesNotOpenThePlannerSheetOnItsOwn() {
        assertFalse(
            "a ready route must show the read-only card, not the planner sheet",
            routePlanSheetShown(RoutePlanStatus.Ready, stopsOpen = false),
        )
    }

    @Test
    fun aReadyRouteShowsTheReadOnlyCardUntilStopsOpens() {
        assertTrue(routeSummaryCardShown(RoutePlanStatus.Ready, stopsOpen = false))
        assertFalse("the sheet takes over while Stops is open", routeSummaryCardShown(RoutePlanStatus.Ready, stopsOpen = true))
    }

    @Test
    fun theStopsActionOpensTheSheetOnAReadyRoute() {
        assertTrue(routePlanSheetShown(RoutePlanStatus.Ready, stopsOpen = true))
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
            assertTrue("$status needs the sheet's hints and actions", routePlanSheetShown(status, stopsOpen = false))
        }
    }

    @Test
    fun anIdlePlanComposesNoSheet() {
        assertFalse(routePlanSheetShown(RoutePlanStatus.Idle, stopsOpen = false))
        assertFalse(routePlanSheetShown(RoutePlanStatus.Idle, stopsOpen = true))
    }
}
