package nl.r1a.grassmaps.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-0612 final-review defect: the map bar's chevron opened the saved-lists
 * sheet alone, while its label promised widgets and Saved. The chevron is a
 * permanent part of the single bottom bar, has a 48 dp target and now opens a
 * sheet with both the widgets and the saved lists. Mappers are Composables a
 * JVM test cannot render, so pin the composition and the label from source and
 * resources.
 */
class MapBottomBarChevronTest {
    private val source = File(HOST_FILE).readText()

    @Test
    fun theBarEndsWithAChevronThatOpensTheWidgetsAndSavedSheet() {
        val bar = source.substringAfter("private fun MapBottomBar(").substringBefore("\n}\n")

        assertTrue("the default bottom bar has no chevron", bar.contains("BottomBarChevron("))
        assertTrue(
            "the chevron is not at the right end of the bar",
            bar.indexOf("BottomBarChevron(") > bar.indexOf("ProfileSwitchBar("),
        )
        assertTrue(
            "the chevron still opens Saved alone",
            !bar.substringAfter("BottomBarChevron(").contains("FavouritesListsSession.show()"),
        )
        assertTrue(
            "the chevron does not open the combined sheet",
            bar.substringAfter("BottomBarChevron(").contains("onClick = onOpenSheet"),
        )
        assertTrue(
            "the app no longer hosts the combined sheet",
            source.contains("WidgetsSavedSheet("),
        )
    }

    @Test
    fun theSheetContainsWidgetsAndSavedLists() {
        val sheet =
            source
                .substringAfter("if (widgetsSavedSheetOpen && !navigationActive) {")
                .substringBefore("\n        }")

        assertTrue("the sheet has no widgets section", sheet.contains("widgets = {"))
        assertTrue("the widgets section does not compose the widget model", sheet.contains("WidgetGridHost("))
        assertTrue("widgets are not read-only in the sheet", sheet.contains("isEditing = false"))
        assertTrue("the sheet has no saved section", sheet.contains("saved = {"))
        assertTrue("the saved section does not compose the lists body", sheet.contains("MapSavedListsHost("))
        assertTrue("the saved section kept the standalone chrome", sheet.contains("embedded = true"))
        assertTrue("the saved-only sheet was not kept as a separate chevron target", !sheet.contains("FavouritesListsSession.show()"))
        assertTrue(
            "the sheet lost its route to Settings",
            sheet.contains("onOpenSettings =") && sheet.contains("SettingsIndexSession.show()"),
        )
    }

    @Test
    fun theChevronIsAFullTouchTargetWithASpokenName() {
        assertTrue(
            "the 48 dp chevron target is not a named constant",
            Regex("""(?:private |internal )const val SHEET_CHEVRON_TARGET_DP = 48""").containsMatchIn(source),
        )
        val button = source.substringAfter("private fun BottomBarChevron(").substringBefore("\n}\n")
        assertTrue("the chevron target is smaller than 48 dp", button.contains(".size(SHEET_CHEVRON_TARGET_DP.dp)"))
        assertTrue("the chevron has no button role", button.contains("role = Role.Button"))
        assertTrue("the chevron reads as its glyph", button.contains("contentDescription = label"))
        assertTrue(
            "the bar does not pass the translated label",
            source.contains("stringResource(R.string.map_bottom_bar_open_sheet)"),
        )
    }

    @Test
    fun theChevronTargetClearsThe48DpRuleAndFitsBelowLayers() {
        val target =
            Regex("""(?:private |internal )const val SHEET_CHEVRON_TARGET_DP = (\d+)""")
                .find(source)
                ?.groupValues
                ?.get(1)
                ?.toInt()
                ?: throw AssertionError("the chevron has no numeric target")
        assertTrue("the chevron target is under 48 dp", target >= 48)
        assertTrue(
            "Layers at ${mapBottomInsets(arc = false).layersDp} dp does not clear the $target dp chevron",
            mapBottomInsets(arc = false).layersDp > target,
        )
    }

    @Test
    fun theChevronLabelNamesTheSheetInBothLocales() {
        val english = string(ENGLISH, LABEL_KEY)
        val ukrainian = string(UKRAINIAN, LABEL_KEY)

        assertTrue("English chevron label is missing", english.isNotBlank())
        assertTrue("Ukrainian chevron label is missing", ukrainian.isNotBlank())
        assertTrue("the label is not translated", english != ukrainian)
        assertTrue(
            "English label does not name widgets: '$english'",
            english.contains("widget", ignoreCase = true),
        )
        assertTrue(
            "English label does not name Saved: '$english'",
            english.contains("saved", ignoreCase = true),
        )
        assertTrue(
            "Ukrainian label does not name widgets: '$ukrainian'",
            ukrainian.contains("віджет", ignoreCase = true),
        )
        assertTrue(
            "Ukrainian label does not name Saved: '$ukrainian'",
            ukrainian.contains("збережен", ignoreCase = true),
        )
        assertEquals("the label is a phrase in English", true, english.contains(' '))
        assertNotEquals("both locales read the same", english, ukrainian)
    }

    private fun string(
        path: String,
        key: String,
    ): String =
        Regex("""<string name="${Regex.escape(key)}">([^<]*)</string>""")
            .find(File(path).readText())
            ?.groupValues
            ?.get(1)
            .orEmpty()

    private companion object {
        const val HOST_FILE = "src/main/java/nl/r1a/grassmaps/app/MapScreen.kt"
        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"
        const val LABEL_KEY = "map_bottom_bar_open_sheet"
    }
}
