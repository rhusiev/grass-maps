package nl.r1a.grassmaps.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Final blind review 6 P2-3: the chevron sheet stacked "Saved", "Lists" and
 * "Saved" above one empty saved section. The saved section keeps its own
 * heading, so the sheet and the embedded lists host must stop drawing a second
 * and a third one. Composables cannot render in a JVM test, so pin the source
 * shape and the sections that must survive the change.
 */
class SheetHeadingUniquenessTest {
    private val sheet = File(SHEET_FILE).readText()
    private val host = File(HOST_FILE).readText()
    private val section = File(SECTION_FILE).readText()

    @Test
    fun theSavedHeadingIsComposedOnceInTheCombinedSheet() {
        assertEquals(
            "the sheet still draws its own Saved title above the lists body",
            0,
            Regex("""text\s*=\s*savedTitle""").findAll(sheet).count(),
        )

        assertTrue(
            "the embedded body still draws the back chevron of the full screen",
            host.contains("onBack = actions.onClose.takeIf { !embedded }"),
        )
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
        assertTrue("the sheet title is gone", sheet.contains("sheetTitle = sheetTitle"))
        assertTrue("the widgets title is gone", sheet.contains("text = widgetsTitle"))
        assertTrue("the widgets body is gone", sheet.contains("widgets()"))
        assertTrue("the saved body is gone", sheet.contains("saved()"))
        assertTrue("the Settings command is gone", sheet.contains("onClick = onOpenSettings"))

        assertTrue("the saved section is gone", host.contains("SavedScreenSection("))
        assertTrue("the new group panel is gone", host.contains("NewGroupPanel("))
        assertTrue("the bottom controls are gone", host.contains("SavedBottomControls("))
        assertTrue("group visibility is not wired", host.contains("onToggleGroupShown = actions.onToggleGroupShown"))
        assertTrue("the map command is not wired", host.contains("onShowOnMap = onShowOnMap"))
        assertTrue("the undo command is not wired", host.contains("onUndo = actions.onUndo"))
        assertTrue("the 48 dp tap target is gone", section.contains("private const val MIN_TAP_TARGET_DP = 48"))
        assertTrue("the colour swatch is not a 48 dp target", section.contains(".size(MIN_TAP_TARGET_DP.dp)"))
    }

    private companion object {
        const val SHEET_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/WidgetsSavedSheet.kt"
        const val HOST_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/FavouritesListsHost.kt"
        const val SECTION_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/SavedScreenHost.kt"
    }
}
