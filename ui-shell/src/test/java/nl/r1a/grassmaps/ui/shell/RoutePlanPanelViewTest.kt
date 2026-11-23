package nl.r1a.grassmaps.ui.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The default planner view must not compose the editable header (T-0612). A
 * private Composable cannot be rendered in this JVM module, so these tests pin
 * the section model the Composable reads. That is weaker than a rendered
 * assertion.
 */
class RoutePlanPanelViewTest {
    @Test
    fun theDefaultPlannerViewDoesNotComposeTheEditableHeader() {
        val sections = routePlanSheetSections(DEFAULT_ROUTE_PLAN_SHEET_VIEW)

        assertFalse("the default view has no undo, redo, swap or coordinate field", sections.editableHeader)
        assertFalse("the default view has no editable stop list", sections.stopList)
    }

    @Test
    fun stopsStillReachesTheExistingEditableHeaderAndStopList() {
        val sections = routePlanSheetSections(RouteStopsAction.OpenPlannerStopsSection.sheetView)

        assertTrue("Stops reaches undo, redo, swap and the coordinate field", sections.editableHeader)
        assertTrue("Stops reaches the planner's existing stop list", sections.stopList)
    }
}
