package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 9 reviews (T-0612): the preview's three frequent actions sit in the
 * sheet's pinned bar at the bottom, within thumb reach, and Save sits beside
 * the route summary it saves instead of alone on its own row. The JVM suite
 * cannot render the sheet, so the labels and the source shape are pinned
 * here; the rendered bounds are checked on a device.
 */
class RouteCardActionBarTest {
    @Test
    fun theBarSpeaksTheExistingActionNamesInEnglish() {
        val strings = catalog(APP_ENGLISH, SHELL_ENGLISH)

        assertEquals(
            listOf("Stops", "Details", "Start", "Hide details", "Done", "Reset to profile", "Apply"),
            RoutePlanBarAction.entries.map { routePlanBarLabel(it, strings) },
        )
    }

    @Test
    fun theBarSpeaksTheExistingActionNamesInUkrainian() {
        val strings = catalog(APP_UKRAINIAN, SHELL_UKRAINIAN)

        assertEquals("Подробиці", routePlanBarLabel(RoutePlanBarAction.Details, strings))
        assertEquals("Сховати подробиці", routePlanBarLabel(RoutePlanBarAction.HideDetails, strings))
        assertEquals("Готово", routePlanBarLabel(RoutePlanBarAction.Done, strings))
        for (action in RoutePlanBarAction.entries) {
            assertTrue("$action has no Ukrainian label", strings.contains(routePlanBarLabelKey(action)))
        }
    }

    @Test
    fun saveSitsBesideTheSummaryAndOutsideTheBar() {
        val summary = panel().substringAfter("private fun RouteSummarySection(").substringBefore("\n}\n")

        assertTrue(
            "Save is not beside the summary values",
            Regex("""Row\([\s\S]*ActionChip\(text = preview\.save""").containsMatchIn(summary),
        )
        assertFalse("Save must not be a bar action", RoutePlanBarAction.entries.any { it.name == "Save" })
    }

    @Test
    fun theBarRunsThePlannersOwnCommandsAndViewChanges() {
        val panel = panel()

        assertTrue("Start lost the planner's command", "RoutePlanBarAction.Start -> onStart()" in panel)
        assertTrue("Apply lost the draft", "RoutePlanBarAction.Apply -> onApplyPreferences(draft)" in panel)
        assertTrue("Reset lost the planner's command", "RoutePlanBarAction.Reset -> onResetPreferences()" in panel)
        assertTrue("a bar press does not move to its view", "action.next?.let(onViewChange)" in panel)
    }

    private fun catalog(
        app: String,
        shell: String,
    ): Strings = TestStringCatalog.read(app).merge(TestStringCatalog.read(shell)).resolver()

    private fun panel(): String = File("src/main/java/nl/r1a/grassmaps/ui/shell/RoutePlanPanel.kt").readText()

    private companion object {
        const val APP_ENGLISH = "../app/src/main/res/values/strings.xml"
        const val APP_UKRAINIAN = "../app/src/main/res/values-uk/strings.xml"
        const val SHELL_ENGLISH = "src/main/res/values/strings.xml"
        const val SHELL_UKRAINIAN = "src/main/res/values-uk/strings.xml"
    }
}
