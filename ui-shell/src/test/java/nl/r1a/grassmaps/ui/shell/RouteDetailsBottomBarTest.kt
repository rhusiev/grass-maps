package nl.r1a.grassmaps.ui.shell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round-4 design-aware P1 3, second slice: route details end in the canon's
 * pinned Hide details / Start bar. The JVM suite cannot render the sheet, so
 * the actions, their wiring and their touch targets are pinned in source.
 */
class RouteDetailsBottomBarTest {
    @Test
    fun theDetailsViewPinsAHideDetailsStartBar() {
        val panel = routePanel()
        val call = panel.substringAfter("RouteDetailsBottomBar(").substringBefore("\n        }")

        assertTrue("no pinned details bar", "private fun RouteDetailsBottomBar(" in panel)
        assertTrue("Hide details must return to the read-only card", "onHide = onDoneStops" in call)
        assertTrue("Start must reuse the planner's command", "onStart = onStart" in call)
        assertTrue("the bar reuses the Hide details copy", "strings.resolve(ROUTE_DETAILS_HIDE_KEY)" in call)
        assertTrue("the bar reuses the preview's Start copy", "startLabel = preview.start" in call)
        assertTrue("the bar must be last in the sheet", panel.indexOf("RouteDetailsBottomBar(") > panel.indexOf("RecentRoutes("))
    }

    @Test
    fun hideDetailsReturnsToTheCardWithoutChangingThePlan() {
        val panel = routePanel()
        val call = panel.substringAfter("RouteDetailsBottomBar(").substringBefore("\n        }")

        assertTrue("Hide details changed the plan", "onHide = onClose" !in call)
        assertTrue("Hide details must not clear the plan", "RoutePlannerContract.clear" !in call)
        assertTrue(
            "the app's Done action returns to the read-only card",
            "onDoneStops = { routeSheetView = RoutePlanSheetView.Summary }" in mapScreen(),
        )
    }

    @Test
    fun theDetailsViewHasOneHideDetailsAction() {
        val panel = routePanel()
        val toggle = panel.substringAfter("if (!sections.details)").substringBefore("if (detailsExpanded)")

        assertTrue("the inline toggle moved out of the details view", "AnalysisDetailsToggle(" in toggle)
        val expanded = panel.substringAfter("if (detailsExpanded) {").substringBefore("RouteDetailsBottomBar(")
        assertTrue("the details view must not compose a second inline toggle", "AnalysisDetailsToggle(" !in expanded)
    }

    @Test
    fun theBottomBarKeeps48DpTargetsAndThePrimaryStartFace() {
        val bar = routePanel().substringAfter("private fun RouteDetailsBottomBar(").substringBefore("\n}\n")

        assertTrue(
            "the bottom bar lost a 48 dp minimum",
            Regex("""\.sizeIn\(minHeight = CONTROL_TARGET_DP\.dp\)""").findAll(bar).count() == 2,
        )
        assertTrue("Hide details lost its command", "role = Role.Button, onClick = onHide" in bar)
        assertTrue("Start lost its command", "role = Role.Button, onClick = onStart" in bar)
        assertTrue("Start lost its primary colour", "background(ROUTE_ACTION_COLOR" in bar)
        assertTrue("Hide details lost its spoken label", "contentDescription = hideLabel" in bar)
        assertTrue("Start lost its spoken label", "contentDescription = startLabel" in bar)
    }

    @Test
    fun theJourneyHeaderGroupingAndWarningRowsAreUntouched() {
        val panel = routePanel()

        assertTrue("SHOW ALONG grouping was removed", "showAlong = true" in panel)
        assertTrue("the up/down/steepest header was removed", "summary = detailsSummary" in panel)
        assertTrue("the range callback was removed", "onRange =" in panel)
        assertTrue("the warnings row was removed", "RouteWarningsEntry(" in panel)
        assertTrue("the preferences row was removed", "RoutePreferencesEntry(" in panel)
        assertTrue("colour selection returned to route details", "showColour = true" !in panel)
    }

    private fun routePanel(): String = File("src/main/java/nl/r1a/grassmaps/ui/shell/RoutePlanPanel.kt").readText()

    private fun mapScreen(): String = File("../app/src/main/java/nl/r1a/grassmaps/app/MapScreen.kt").readText()
}
