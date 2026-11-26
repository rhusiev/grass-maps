package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.plugin.api.model.RoutePlanStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 9 reviews (T-0612): a ready route is round 8 `x-route-preview`. The
 * read-only From/To card sits at the top, and the planner sheet at the bottom
 * holds the preview and every press. The picking, planning and failed states
 * keep the sheet, because it carries their hints and recovery actions.
 */
class RoutePlannerDefaultViewTest {
    @Test
    fun aReadyRouteShowsThePreviewSheet() {
        assertTrue(
            "a ready route keeps its presses in the bottom sheet",
            routePlanSheetShown(RoutePlanStatus.Ready),
        )
    }

    @Test
    fun onlyAReadyRouteShowsTheReadOnlyCard() {
        assertTrue(routeSummaryCardShown(RoutePlanStatus.Ready))
        for (status in RoutePlanStatus.entries - RoutePlanStatus.Ready) {
            assertFalse("$status has no route ends to show", routeSummaryCardShown(status))
        }
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
            assertTrue("$status needs the sheet's hints and actions", routePlanSheetShown(status))
        }
    }

    @Test
    fun anIdlePlanComposesNoSheet() {
        assertFalse(routePlanSheetShown(RoutePlanStatus.Idle))
    }
}
