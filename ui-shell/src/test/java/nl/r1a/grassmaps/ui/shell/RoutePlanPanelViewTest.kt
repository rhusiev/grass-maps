package nl.r1a.grassmaps.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The JVM suite cannot render the planner (T-0612). These tests check the
 * section model and the dismissal wiring, not the rendered controls.
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

    @Test
    fun plannerCloseIsComposedBeforeTheEditableHeaderAndStatusBranches() {
        val source =
            File("src/main/java/nl/r1a/grassmaps/ui/shell/RoutePlanPanel.kt")
                .readText()
        val panel = source.substringBefore("private fun PanelControls(")
        val controls = panel.indexOf("PanelControls(")

        assertTrue("Summary and Stops both draw the controls", controls in 0 until panel.indexOf("if (sections.editableHeader)"))
        assertTrue("Even an early return in the failure branch leaves Close visible", controls < panel.indexOf("when (state.status)"))
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
        assertEquals("searchExpanded || plannerSheetShown || layersSheetShown", enabled)
        assertEquals(
            listOf("searchExpanded -> searchExpanded = false", "plannerSheetShown -> closePlan()", "layersSheetShown -> dismissLayers()"),
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

    private fun mapScreen(): String = File("../app/src/main/java/nl/r1a/grassmaps/app/MapScreen.kt").readText()
}
