package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.RoutePlanState
import nl.r1a.grassmaps.plugin.api.model.RoutePlanStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The JVM suite cannot render the planner (T-0612). These tests check the
 * layout model and the dismissal wiring, not the rendered controls.
 */
class RoutePlanPanelViewTest {
    @Test
    fun theDefaultPlannerViewDoesNotComposeTheEditableHeader() {
        val sections = routePlanSheetLayout(DEFAULT_ROUTE_PLAN_SHEET_VIEW, READY).sections

        assertFalse("the default view has no undo, redo, swap or coordinate field", RoutePlanSection.EditableHeader in sections)
        assertFalse("the default view has no editable stop list", RoutePlanSection.StopList in sections)
    }

    @Test
    fun stopsStillReachesTheExistingEditableHeaderAndStopList() {
        val sections = routePlanSheetLayout(RoutePlanBarAction.Stops.next ?: error("no view"), READY).sections

        assertTrue("Stops reaches undo, redo, swap and the coordinate field", RoutePlanSection.EditableHeader in sections)
        assertTrue("Stops reaches the planner's existing stop list", RoutePlanSection.StopList in sections)
    }

    @Test
    fun detailsOpensTheExistingChartSectionWithoutTheEditableHeader() {
        val sections = routePlanSheetLayout(RoutePlanBarAction.Details.next ?: error("no view"), READY).sections

        assertTrue("Details opens the planner's chart section", RoutePlanSection.Chart in sections)
        assertFalse("Details keeps the read-only header", RoutePlanSection.EditableHeader in sections)
        assertFalse("Details does not open the stop list", RoutePlanSection.StopList in sections)
        assertFalse(
            "the preview keeps its chart section closed",
            RoutePlanSection.Chart in routePlanSheetLayout(DEFAULT_ROUTE_PLAN_SHEET_VIEW, READY).sections,
        )
    }

    @Test
    fun plannerCloseIsComposedBeforeTheEditableHeaderAndStatusBranches() {
        val source =
            File("src/main/java/nl/r1a/grassmaps/ui/shell/RoutePlanPanel.kt")
                .readText()
        val panel = source.substringBefore("private fun PanelControls(")
        val controls = panel.indexOf("PanelControls(")

        assertTrue("Every view draws the controls before its sections", controls in 0 until panel.indexOf("layout.sections.forEach"))
        assertTrue("The controls are the sheet's fixed header", panel.contains("header = {"))
        assertTrue("The header forwards the Close action", panel.contains("onClose = onClose"))
        val header = source.substringAfter("private fun PanelControls(").substringBefore("private fun ControlGlyph(")
        assertTrue(
            "Close is outside the editable-only block",
            Regex("""\n        }\n        ControlGlyph\(\n            glyph = CLOSE_GLYPH,""").containsMatchIn(header),
        )
    }

    @Test
    fun mapBackDismissesOnlyTheFirstVisibleOverlayInSearchPlannerLayersOrder() {
        val handler =
            Regex("""BackHandler\(\s*enabled = !navigationActive && \(([^)]+)\),?\s*\) \{\s*when \{\s*([^}]+)\}\s*\}""")
                .find(mapScreen())

        assertTrue("Map Back is enabled only while a map overlay is visible", handler != null)
        val (enabled, actions) = requireNotNull(handler).destructured
        assertEquals("widgetsSavedSheetOpen || searchExpanded || plannerSheetShown || layersSheetShown", enabled)
        assertEquals(
            listOf(
                "widgetsSavedSheetOpen -> widgetsSavedSheetOpen = false",
                "searchExpanded -> searchExpanded = false",
                "plannerSheetShown && plannerBackView != null -> routeSheetView = plannerBackView",
                "plannerSheetShown -> closePlan()",
                "layersSheetShown -> dismissLayers()",
            ),
            actions.trim().lines().map { it.trim() },
        )
    }

    @Test
    fun plannerCloseAndBackShareTheActionThatClearsThePlan() {
        val map = mapScreen()
        val close = map.substringAfter("val closePlan:").substringBefore("BackHandler(")
        val panel = map.substringAfter("\n            RoutePlanPanel(").substringBefore("val trackCardTrack")

        assertTrue("The panel uses the shared action", panel.contains("onClose = closePlan"))
        assertTrue("Dismissal clears the plan instead of only hiding the sheet", close.contains("RoutePlannerContract.clear"))
    }

    @Test
    fun mapBackCollapsesAnOpenPlannerViewBeforeItClearsThePlan() {
        val map = mapScreen()

        assertTrue(
            "Back does not read the planner's back view",
            map.contains("val plannerBackView = routePlanBackView(routeSheetView)"),
        )
    }

    private fun mapScreen(): String = File("../app/src/main/java/nl/r1a/grassmaps/app/MapScreen.kt").readText()

    private companion object {
        val READY = RoutePlanState(status = RoutePlanStatus.Ready)
    }
}
