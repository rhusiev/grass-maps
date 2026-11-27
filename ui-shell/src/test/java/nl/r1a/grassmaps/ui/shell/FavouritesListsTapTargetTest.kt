package nl.r1a.grassmaps.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Final blind review P2-1: the colour swatches were 20 dp squares and Create
 * list had no minimum height. Round 8 moves both into the + panel and the
 * group's colour row; the colours and commands stay, and every target keeps the
 * shell's 48 dp minimum. Composables cannot render in a JVM test, so pin the
 * source shape and the preset values.
 */
class FavouritesListsTapTargetTest {
    private val source = File(SECTION_FILE).readText()
    private val host = File(HOST_FILE).readText()

    @Test
    fun colourSwatchesAreFullTouchTargets() {
        val swatches = source.substringAfter("private fun Swatches(").substringBefore("\n}\n")

        assertTrue("the swatch size is not the tap target", swatches.contains(".size(MapControlMetrics.TAP_TARGET_DP.dp)"))
        assertTrue(
            "the swatch is selectable only inside its inset",
            swatches.indexOf(
                ".selectable(",
            ) in swatches.indexOf(".size(MapControlMetrics.TAP_TARGET_DP.dp)")..swatches.indexOf(".padding("),
        )
        assertEquals("the shared tap target is not 48 dp", 48, MapControlMetrics.TAP_TARGET_DP)
        assertTrue("the colour command changed", swatches.contains("onChoose(preset)"))
        assertEquals(6, PRESET_COLOURS.size)
    }

    @Test
    fun theColourSwatchesWrapInsteadOfClippingOnNarrowScreens() {
        val swatches = source.substringAfter("private fun Swatches(").substringBefore("\n}\n")

        assertTrue("the swatches still use one fixed row", swatches.contains("FlowRow("))
        val swatchGap =
            Regex("""SWATCH_GAP_DP = (\d+)""")
                .find(source)
                ?.groupValues
                ?.get(1)
                ?.toInt() ?: 0
        val indent =
            Regex("""PLACE_INDENT_DP = (\d+)""")
                .find(source)
                ?.groupValues
                ?.get(1)
                ?.toInt() ?: 0
        val unwrappedWidth = indent + PRESET_COLOURS.size * 48 + (PRESET_COLOURS.size - 1) * swatchGap
        assertEquals("the single-row width the narrow screen clipped", 374, unwrappedWidth)
    }

    @Test
    fun createIsTheSharedFullWidthButtonWithItsOriginalCommand() {
        val shared = File("src/main/java/nl/r1a/grassmaps/ui/shell/ListActionButtons.kt").readText()
        val button = shared.substringAfter("internal fun ListActionButton(").substringBefore("\n}\n")

        assertTrue("the shared button has no minimum height", button.contains(".heightIn(min = MapControlMetrics.TAP_TARGET_DP.dp)"))
        assertTrue("the shared button cannot span a panel", button.contains("Modifier.fillMaxWidth()"))
        assertTrue(
            "Create lost its command",
            source.contains(
                "text = text.create",
            ) && source.contains("enabled = name.isNotBlank()") && source.contains("onClick = onCreate"),
        )
        assertTrue("the host no longer creates the list", host.contains("actions.onCreateList(name, newColor)"))
    }

    @Test
    fun theSavedPanelsUseTheSharedSheetWithAPaneTitle() {
        assertTrue("the panel still draws its own scrim", !host.contains("colors.scrim"))
        assertTrue("the panel still draws its own frame", !source.contains("SavedPanelFrame("))
        assertTrue("the shared sheet is not used", host.contains("ShellBottomSheet("))
        assertTrue("the sheet has no pane title", host.contains("sheetTitle ="))
        assertTrue("the sheet has no named close control", host.contains("closeLabel = text.cancel"))
    }

    @Test
    fun theListButtonsAreSharedWithTracks() {
        val tracks = File("src/main/java/nl/r1a/grassmaps/ui/shell/TrackLibraryHost.kt").readText()

        assertTrue("Saved keeps a private copy of the button", !source.contains("private fun PillButton("))
        assertTrue("Saved keeps a private copy of the undo pill", !source.contains("private fun UndoPill("))
        assertTrue("Tracks keeps private copies of the buttons", !tracks.contains("private fun PrimaryButton("))
        assertTrue("Tracks does not use the shared button", tracks.contains("ListActionButton("))
        assertTrue("Saved does not use the shared undo bar", source.contains("ListUndoBar("))
    }

    @Test
    fun rowActionsAreFullTouchTargetsToo() {
        val action = source.substringAfter("private fun ActionRow(").substringBefore("\n}\n")
        val more = source.substringAfter("private fun MoreLink(").substringBefore("\n}\n")

        assertTrue("a place action has no minimum height", action.contains(".heightIn(min = MapControlMetrics.TAP_TARGET_DP.dp)"))
        assertTrue("the more line has no minimum height", more.contains(".heightIn(min = MapControlMetrics.TAP_TARGET_DP.dp)"))
    }

    private companion object {
        const val SECTION_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/SavedScreenHost.kt"
        const val HOST_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/FavouritesListsHost.kt"
    }
}
