package nl.r1a.grassmaps.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Final blind review round 2 P2-2: the planner action chips, the Start chip,
 * the elevation axis chips and the Details toggle had only text padding, so
 * their clickable area measured about 30, 26 and 19 dp. They cannot be
 * measured on a device without a route, so pin the modifier shape in source.
 * The command, label, selected state and colour tokens are pinned too, so a
 * minimum cannot be added by replacing the control with a different one.
 */
class ControlTargetMinimumTest {
    private val routePlan = File(ROUTE_PANEL_FILE).readText()
    private val elevation = File(ELEVATION_FILE).readText()

    @Test
    fun routePlanReusesIts48DpTouchTargetConstant() {
        assertEquals(
            "the planner's shared touch target changed",
            "48",
            Regex("""private const val CONTROL_TARGET_DP = (\d+)""")
                .find(routePlan)
                ?.groupValues
                ?.get(1),
        )
    }

    @Test
    fun actionChipHasA48DpMinimumInBothDimensions() {
        val chip = routePlan.substringAfter("internal fun ActionChip(").substringBefore("\n}\n")

        assertTrue(
            "ActionChip has no 48 dp minimum in both dimensions",
            chip.contains(".sizeIn(minWidth = CONTROL_TARGET_DP.dp, minHeight = CONTROL_TARGET_DP.dp)"),
        )
        assertTrue("ActionChip lost its command", chip.contains("Role.Button, onClick = onClick"))
        assertTrue(
            "ActionChip lost its primary colour branch",
            chip.contains("if (primary) ROUTE_ACTION_INK else ROUTE_VALUE_COLOR"),
        )
    }

    @Test
    fun startChipHasA48DpMinimumInBothDimensions() {
        val chip = routePlan.substringAfter("private fun StartChip(").substringBefore("\n}\n")

        assertTrue(
            "StartChip has no 48 dp minimum in both dimensions",
            chip.contains(".sizeIn(minWidth = CONTROL_TARGET_DP.dp, minHeight = CONTROL_TARGET_DP.dp)"),
        )
        assertTrue("StartChip lost its command", chip.contains("Role.Button, onClick = onClick"))
        assertTrue("StartChip lost its primary colour", chip.contains("background(ROUTE_ACTION_COLOR"))
    }

    @Test
    fun elevationChipHasA48DpMinimumInBothDimensions() {
        assertEquals(
            "the elevation tap target changed",
            "48",
            Regex("""private const val MIN_TAP_TARGET_DP = (\d+)""")
                .find(elevation)
                ?.groupValues
                ?.get(1),
        )
        val chip = elevation.substringAfter("private fun ElevationChip(").substringBefore("\n}\n")

        assertTrue(
            "ElevationChip has no 48 dp minimum in both dimensions",
            chip.contains(".sizeIn(minWidth = MIN_TAP_TARGET_DP.dp, minHeight = MIN_TAP_TARGET_DP.dp)"),
        )
        assertTrue(
            "ElevationChip lost its selected-state command",
            chip.contains(".selectable(selected = selected, role = Role.RadioButton, onClick = onClick)"),
        )
        assertTrue("ElevationChip lost its selected colours", chip.contains("palette.chipSelected"))
    }

    @Test
    fun detailsToggleHasA48DpMinimumInBothDimensions() {
        val toggle = elevation.substringAfter("fun AnalysisDetailsToggle(").substringBefore("\n}\n")

        assertTrue(
            "AnalysisDetailsToggle has no 48 dp minimum in both dimensions",
            toggle.contains(".sizeIn(minWidth = MIN_TAP_TARGET_DP.dp, minHeight = MIN_TAP_TARGET_DP.dp)"),
        )
        assertTrue("Details toggle lost its command", toggle.contains("Role.Button, onClick = onClick"))
        assertTrue("Details toggle lost its label", toggle.contains("DETAILS_HIDE_KEY") && toggle.contains("DETAILS_SHOW_KEY"))
        assertTrue("Details toggle lost its spoken label", toggle.contains("contentDescription = strings.resolve(key)"))
        assertTrue("Details toggle lost its colour", toggle.contains("colour: Color = ROUTE_VALUE_COLOR"))
    }

    private companion object {
        const val ROUTE_PANEL_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/RoutePlanPanel.kt"
        const val ELEVATION_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/ElevationChart.kt"
    }
}
