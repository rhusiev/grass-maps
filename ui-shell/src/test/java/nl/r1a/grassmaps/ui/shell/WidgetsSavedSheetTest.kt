package nl.r1a.grassmaps.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 9 `k-half`: the chevron's sheet holds the widget cards with Edit and
 * Add, then the Saved tiles with "All N", and no embedded list form.
 * [WidgetsSavedSheet] is a Composable a JVM test cannot render, so pin the one
 * shell chrome, the sections, the 48 dp targets and the translated labels from
 * source and resources. Rendered layout is checked on the AVD.
 */
class WidgetsSavedSheetTest {
    private val sheet = File(SHEET_FILE).readText()
    private val parts = File(PARTS_FILE).readText()

    @Test
    fun theSheetReusesTheOneShellChrome() {
        assertTrue("the sheet does not use the shared bottom-sheet chrome", sheet.contains("ShellBottomSheet("))
        assertTrue("the sheet draws its own scrim", !sheet.contains("colors.scrim"))

        assertTrue(
            "the handle is not the sheet's dismiss control",
            sheet.contains("handleLabel = stringResource(R.string.map_sheet_close)"),
        )
        assertTrue("the sheet kept a close button", !sheet.contains("closeLabel ="))
        assertTrue("the sheet reports a second bottom-chrome key", !sheet.contains("reportsBottomChrome("))
        assertTrue("the map bar is not pinned at the sheet foot", sheet.contains("footer = { bar() }"))
        assertTrue("the bar height is not counted in the sheet bound", sheet.contains("dismissHandleChromeHeight(footer = barHeight)"))
    }

    @Test
    fun theSheetHoldsWidgetCardsAndSavedTilesWithoutTheListForm() {
        assertTrue("the widgets section is missing", sheet.contains("WidgetCardRows("))
        assertTrue("the Saved tile grid is missing", sheet.contains("SavedTileGrid("))
        assertTrue("the empty Saved note is missing", sheet.contains("R.string.map_sheet_saved_empty"))
        assertTrue(
            "the sheet embeds the Saved list form",
            !sheet.contains("FavouritesListsBody(") && !sheet.contains("FavouritesListsHost("),
        )
        assertTrue("Edit is not wired", sheet.contains("onAction = onEdit"))
        assertTrue("All N is not wired", sheet.contains("onAction = actions.onOpenAllSaved"))
        assertTrue("a tile tap is not wired", sheet.contains("SavedTileGrid(saved.tiles, actions.onOpenTile)"))
        assertTrue("Add is not wired", sheet.contains("onAdd = onEdit"))
    }

    @Test
    fun settingsFollowsTheHalfViewInTheScrollingContent() {
        val content = sheet.substringAfter("content = {").substringBefore("\n        },")
        assertTrue("the Settings row is not in the scrolling content", content.contains("SheetSettingsRow("))
        assertTrue(
            "Settings must follow the widgets and Saved sections",
            content.indexOf("SheetSettingsRow(") > content.indexOf("SavedSection("),
        )
        assertTrue("the half view is not measured apart from Settings", content.contains("heights.half = size.height"))
        assertTrue("the sheet does not open at a whole tile row", sheet.contains("peekStops = heights.stops(rowCount, density)"))
    }

    @Test
    fun everyControlHasAFortyEightDpMinimumAndTextIsNeverHeightBound() {
        val tap = "MapControlMetrics.TAP_TARGET_DP.dp"
        val heading = function(parts, "SheetSectionHeading")
        assertTrue("the section action is under 48 dp", heading.contains("minWidth = $tap, minHeight = $tap"))
        assertTrue("the Add tile is under 48 dp tall", function(parts, "AddWidgetTile").contains("minHeight = $tap"))
        assertTrue("the Settings row is under 48 dp tall", function(parts, "SheetSettingsRow").contains("heightIn(min = $tap)"))
        val tile = function(parts, "SavedTileView")
        assertTrue("the Saved tile is not a button", tile.contains("clickable(role = Role.Button"))
        assertTrue(
            "the tile's click sits inside its inset",
            tile.indexOf(".clickable(") < tile.indexOf(".padding("),
        )
        assertTrue("a text container has an exact height", !Regex("""\.height\(\w+_DP\.dp\)""").containsMatchIn(parts))
    }

    @Test
    fun theSheetLabelsAreTranslated() {
        val english = strings(ENGLISH)
        val ukrainian = strings(UKRAINIAN)
        SHEET_KEYS.forEach { key ->
            assertTrue("English misses $key", english[key]?.isNotBlank() == true)
            assertTrue("Ukrainian misses $key", ukrainian[key]?.isNotBlank() == true)
            assertTrue("$key is not translated", english[key] != ukrainian[key])
        }
        assertEquals("Settings", english["map_sheet_settings"])
        assertEquals("Налаштування", ukrainian["map_sheet_settings"])
        assertTrue("the removed read-only note came back", "map_sheet_widgets_read_only" !in english)
    }

    private fun function(
        source: String,
        name: String,
    ): String = source.substringAfter("fun $name(").substringBefore("\n}\n")

    private fun strings(path: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(File(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        const val SHEET_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/WidgetsSavedSheet.kt"
        const val PARTS_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/WidgetsSavedSheetParts.kt"
        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"
        val SHEET_KEYS =
            listOf(
                "map_sheet_title",
                "map_sheet_close",
                "map_sheet_widgets_heading",
                "map_sheet_widgets_edit",
                "map_sheet_widgets_edit_description",
                "map_sheet_widgets_add",
                "map_sheet_widgets_add_description",
                "map_sheet_saved_all",
                "map_sheet_saved_all_description",
                "map_sheet_saved_empty",
                "map_sheet_track_length",
                "map_sheet_saved_title",
                "map_sheet_settings",
            )
    }
}
