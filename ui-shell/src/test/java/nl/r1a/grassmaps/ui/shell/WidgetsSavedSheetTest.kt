package nl.r1a.grassmaps.ui.shell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-0612 final-review P0 2: the chevron's sheet must hold widgets and saved
 * lists, not Saved alone. [WidgetsSavedSheet] is a Composable a JVM test cannot
 * render, so pin the one shell chrome, the two sections and their translated
 * labels from source and resources.
 */
class WidgetsSavedSheetTest {
    private val sheet = File(SHEET_FILE).readText()

    @Test
    fun theSheetReusesTheOneShellChrome() {
        assertTrue("the combined sheet is missing", sheet.isNotBlank())
        assertTrue("the sheet does not use the shared bottom-sheet chrome", sheet.contains("ShellBottomSheet("))
        assertTrue("the sheet draws its own scrim", !sheet.contains("colors.scrim"))
        assertTrue("the sheet has no close label", sheet.contains("closeLabel = closeLabel"))
        assertTrue("the sheet has no handle", !sheet.contains("SHEET_CORNER_DP"))
        assertTrue("the sheet reports a second bottom-chrome key", !sheet.contains("reportsBottomChrome("))
    }

    @Test
    fun theSheetHoldsWidgetsAndSavedSections() {
        assertTrue("the widgets section is missing", sheet.contains("widgetsTitle"))
        assertTrue("the widgets section is not composed", sheet.contains("widgets()"))
        assertTrue("the saved section is missing", sheet.contains("savedTitle"))
        assertTrue("the saved section is not composed", sheet.contains("saved()"))
        assertTrue("the read-only widget note is missing", sheet.contains("widgetsNote"))
        assertTrue("the sheet lost its Settings route", sheet.contains("onOpenSettings"))
    }

    @Test
    fun theSectionAndNoteLabelsAreTranslated() {
        val keys =
            listOf(
                "map_sheet_widgets_title",
                "map_sheet_saved_title",
                "map_sheet_widgets_read_only",
                "map_sheet_settings",
            )
        listOf("src/main/res/values/strings.xml", "src/main/res/values-uk/strings.xml").forEach { path ->
            val present =
                Regex("""<string name="([^"]+)">([^<]+)</string>""")
                    .findAll(File(path).readText())
                    .associate { it.groupValues[1] to it.groupValues[2] }
            keys.forEach { key -> assertTrue("$path misses $key", present[key]?.isNotBlank() == true) }
            assertTrue(
                "$path does not name the widget types in the note",
                present.getValue("map_sheet_widgets_read_only").contains("Altitude", ignoreCase = true) ||
                    present.getValue("map_sheet_widgets_read_only").contains("Висота", ignoreCase = true),
            )
        }
        val en = value("src/main/res/values/strings.xml", "map_sheet_widgets_read_only")
        val uk = value("src/main/res/values-uk/strings.xml", "map_sheet_widgets_read_only")
        assertTrue("the note is not translated", en != uk)
    }

    private fun value(
        path: String,
        key: String,
    ): String =
        Regex("""<string name="${Regex.escape(key)}">([^<]*)</string>""")
            .find(File(path).readText())
            ?.groupValues
            ?.get(1)
            .orEmpty()

    private companion object {
        const val SHEET_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/WidgetsSavedSheet.kt"
    }
}
