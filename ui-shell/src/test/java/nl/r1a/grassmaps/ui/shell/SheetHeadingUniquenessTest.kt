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

    @Test
    fun theSavedHeadingIsComposedOnceInTheCombinedSheet() {
        assertEquals(
            "the sheet still draws its own Saved title above the lists body",
            0,
            Regex("""text\s*=\s*savedTitle""").findAll(sheet).count(),
        )

        val embeddedBody =
            host.substringAfter("if (embedded) {").substringBefore("return")
        assertTrue(
            "the embedded lists body still draws its Lists header title",
            embeddedBody.contains("showTitle = false"),
        )
        assertTrue(
            "the standalone lists sheet lost its own header title",
            host.contains("showTitle: Boolean = true"),
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
        assertTrue("the list creator is gone", host.contains("ListCreator("))
        assertTrue("the bottom controls are gone", host.contains("SavedBottomControls("))
        assertTrue("group visibility is not wired", host.contains("onToggleGroupShown = onToggleGroupShown"))
        assertTrue("the map command is not wired", host.contains("onShowOnMap = onShowOnMap"))
        assertTrue("the undo command is not wired", host.contains("Header(content = content, onUndo = onUndo"))
        assertTrue("the 48 dp tap target is gone", host.contains("private const val MIN_TAP_TARGET_DP = 48"))
        assertTrue("the colour tap target is gone", host.contains("private const val CHIP_SIZE_DP = 48"))
    }

    private companion object {
        const val SHEET_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/WidgetsSavedSheet.kt"
        const val HOST_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/FavouritesListsHost.kt"
    }
}
