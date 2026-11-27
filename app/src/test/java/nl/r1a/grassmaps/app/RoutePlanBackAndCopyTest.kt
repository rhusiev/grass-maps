package nl.r1a.grassmaps.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The planner sheet's Back closes an open warnings list before it changes view
 * or clears the plan. The JVM suite cannot press Back, so the branch order, the
 * hoisted state and the spoken Done copy are pinned from source.
 */
class RoutePlanBackAndCopyTest {
    private val map = File("src/main/java/nl/r1a/grassmaps/app/MapScreen.kt").readText()
    private val panel = File("../ui-shell/src/main/java/nl/r1a/grassmaps/ui/shell/RoutePlanPanel.kt").readText()

    @Test
    fun backClosesTheWarningsListBeforeMovingTheSheet() {
        val warnings = map.indexOf("plannerSheetShown && plannerWarningsVisible ->")
        val backView = map.indexOf("plannerSheetShown && plannerBackView != null ->")
        val close = map.indexOf("plannerSheetShown -> closePlan()")

        assertTrue("the warnings list has no Back branch", warnings >= 0)
        assertTrue("Back changes view before it closes the warnings", warnings < backView && backView < close)
        assertTrue("the warnings state is not hoisted", map.contains("warningsVisible = plannerWarningsVisible"))
        assertTrue("the panel ignores the host's warnings state", panel.contains("onWarningsVisibleChange(!warningsVisible)"))
    }

    @Test
    fun recentRouteRowsAreFullTouchTargets() {
        val rows = panel.substringAfter("private fun RecentRoutes(").substringBefore("\n}\n")

        assertTrue("a recent route cannot be tapped", rows.contains(".clickable(role = Role.Button)"))
        assertTrue("a recent route is under 48 dp", rows.contains(".heightIn(min = CONTROL_TARGET_DP.dp)"))
    }

    @Test
    fun doneSaysWhatItEndsInBothLocales() {
        assertTrue("Done no longer names what it ends", panel.contains("routePlanBarDescription(action, strings, label)"))
        assertEquals(
            "Finish editing stops",
            label("src/main/res/values/strings.xml", "route_planner_done_description"),
        )
        assertEquals(
            "Завершити редагування зупинок",
            label("src/main/res/values-uk/strings.xml", "route_planner_done_description"),
        )
    }

    private fun label(
        path: String,
        key: String,
    ): String =
        Regex("""<string name="$key">([^<]*)</string>""")
            .find(File(path).readText())
            ?.groupValues
            ?.get(1)
            ?: throw AssertionError("$key is missing from $path")
}
