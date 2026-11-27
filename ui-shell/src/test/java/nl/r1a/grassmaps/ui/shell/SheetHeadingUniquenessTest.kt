package nl.r1a.grassmaps.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Final blind review 6 P2-3: the chevron sheet stacked "Saved", "Lists" and
 * "Saved" above one empty saved section. Round 9 `k-half` replaced the embedded
 * lists body with tiles under one "Saved" heading, and the full Saved screen
 * keeps one header of its own. Composables cannot render in a JVM test, so pin
 * the source shape and the sections that must survive the change.
 */
class SheetHeadingUniquenessTest {
    private val sheet = File(SHEET_FILE).readText()
    private val host = File(HOST_FILE).readText()
    private val section = File(SECTION_FILE).readText()

    @Test
    fun theSavedHeadingIsComposedOnceInTheCombinedSheet() {
        assertEquals(
            "the sheet does not name its Saved section exactly once",
            1,
            Regex("""R\.string\.map_sheet_saved_title""").findAll(sheet).count(),
        )
        assertTrue("the sheet embeds a second Saved heading", !sheet.contains("SavedScreenSection("))

        assertTrue("the lists host kept a body-only mode", !host.contains("embedded"))
        assertTrue(
            "a second Lists header title came back",
            !host.contains("text = content.title"),
        )
        assertEquals(
            "the saved section heading is not composed exactly once",
            1,
            Regex("""SavedScreenSection\(""").findAll(host).count(),
        )
    }

    @Test
    fun theWidgetsAndSavedSectionsStillRenderWithTheirCommands() {
        assertTrue("the sheet title is gone", sheet.contains("sheetTitle = stringResource(R.string.map_sheet_title)"))
        assertTrue("the widgets section is gone", sheet.contains("WidgetsSection("))
        assertTrue("the saved section is gone", sheet.contains("SavedSection("))
        assertTrue("the Settings command is gone", sheet.contains("actions.onOpenSettings"))

        assertTrue("the saved section is gone", host.contains("SavedScreenSection("))
        assertTrue("the new group panel is gone", host.contains("NewGroupPanel("))
        assertTrue("the bottom controls are gone", host.contains("SavedBottomControls("))
        assertTrue("group visibility is not wired", host.contains("onToggleGroupShown = actions.onToggleGroupShown"))
        assertTrue("the map command is not wired", host.contains("onShowOnMap = onShowOnMap"))
        assertTrue("the undo command is not wired", host.contains("onUndo = actions.onUndo"))
        assertEquals("the shared tap target is not 48 dp", 48, MapControlMetrics.TAP_TARGET_DP)
        assertTrue("the colour swatch is not a 48 dp target", section.contains(".size(MapControlMetrics.TAP_TARGET_DP.dp)"))
    }

    private companion object {
        const val SHEET_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/WidgetsSavedSheet.kt"
        const val HOST_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/FavouritesListsHost.kt"
        const val SECTION_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/SavedScreenHost.kt"
    }
}
