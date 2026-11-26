package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.RoutePlanState
import nl.r1a.grassmaps.plugin.api.model.RoutePlanStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round-4 design-aware P1 3, second slice, and round 10 `c-route-details`:
 * route details end in the canon's pinned Hide details / Start bar. The JVM
 * suite cannot render the sheet, so the actions, their wiring and their touch
 * targets are pinned in the model and the source.
 */
class RouteDetailsBottomBarTest {
    @Test
    fun theDetailsViewPinsAHideDetailsStartBar() {
        val layout = routePlanSheetLayout(RoutePlanSheetView.Details, RoutePlanState(status = RoutePlanStatus.Ready))

        assertEquals(listOf(RoutePlanBarAction.HideDetails, RoutePlanBarAction.Start), layout.bar)
        assertTrue("the bar reuses the Hide details copy", "RoutePlanBarAction.HideDetails -> ROUTE_DETAILS_HIDE_KEY" in panel())
        assertTrue("the bar reuses the preview's Start copy", "RoutePlanBarAction.Start -> START_KEY" in panel())
    }

    @Test
    fun hideDetailsReturnsToThePreviewWithoutChangingThePlan() {
        assertEquals(RoutePlanSheetView.Summary, RoutePlanBarAction.HideDetails.next)
        val onAction = panel().substringAfter("val onBarAction:").substringBefore("\n    }\n")

        assertFalse("Hide details must not clear the plan", "onClose" in onAction)
        assertTrue("the app's view change is the only effect of Hide details", "onViewChange = { routeSheetView = it }" in mapScreen())
    }

    @Test
    fun theDetailsViewHasNoInlineToggle() {
        assertFalse(
            "the details view must not compose a second Hide details action",
            "AnalysisDetailsToggle(" in panel(),
        )
    }

    @Test
    fun theBottomBarKeeps48DpTargetsAndThePrimaryStartFace() {
        val bar = panel().substringAfter("internal fun RoutePlanBottomBar(").substringBefore("\n}\n")

        assertTrue("the bottom bar lost its 48 dp minimum", ".heightIn(min = BAR_BUTTON_HEIGHT_DP.dp)" in bar)
        assertTrue("a bar button lost its command", "role = Role.Button, onClick = { onAction(action) }" in bar)
        assertTrue("Start lost its primary colour", "if (action.primary) ROUTE_ACTION_COLOR else ROUTE_CHIP_COLOR" in bar)
        assertTrue("a bar button lost its spoken label", "contentDescription = label" in bar)
    }

    @Test
    fun theJourneyHeaderGroupingAndWarningRowsAreUntouched() {
        val panel = panel()

        assertTrue("SHOW ALONG grouping was removed", "showAlong = true" in panel)
        assertTrue("the up/down/steepest header was removed", "summary = detailsSummary" in panel)
        assertTrue("the range callback was removed", "onRange =" in panel)
        assertTrue("the warnings row was removed", "RouteWarningsEntry(" in panel)
        assertTrue("the preferences row was removed", "RoutePreferencesEntry(" in panel)
        assertTrue("colour selection returned to route details", "showColour = true" !in panel)
    }

    private fun panel(): String = File("src/main/java/nl/r1a/grassmaps/ui/shell/RoutePlanPanel.kt").readText()

    private fun mapScreen(): String = File("../app/src/main/java/nl/r1a/grassmaps/app/MapScreen.kt").readText()
}
