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

        assertTrue("the swatch size is not the tap target", swatches.contains(".size(MIN_TAP_TARGET_DP.dp)"))
        assertTrue("the shell tap target is not 48 dp", source.contains("private const val MIN_TAP_TARGET_DP = 48"))
        assertTrue("the colour command changed", swatches.contains("onChoose(preset)"))
        assertEquals(6, PRESET_COLOURS.size)
    }

    @Test
    fun createIsAFullWidthButtonWithItsOriginalCommand() {
        val pill = source.substringAfter("private fun PillButton(").substringBefore("\n}\n")

        assertTrue("the button has no minimum height", pill.contains(".heightIn(min = BUTTON_DP.dp)"))
        assertTrue("the button is under 48 dp", source.contains("private const val BUTTON_DP = 50"))
        assertTrue(
            "Create lost its command",
            source.contains("PillButton(text = text.create, primary = true, enabled = name.isNotBlank(), onClick = onCreate)"),
        )
        assertTrue("the host no longer creates the list", host.contains("actions.onCreateList(name, newColor)"))
    }

    @Test
    fun rowActionsAreFullTouchTargetsToo() {
        val action = source.substringAfter("private fun ActionRow(").substringBefore("\n}\n")
        val more = source.substringAfter("private fun MoreLink(").substringBefore("\n}\n")

        assertTrue("a place action has no minimum height", action.contains(".heightIn(min = MIN_TAP_TARGET_DP.dp)"))
        assertTrue("the more line has no minimum height", more.contains(".heightIn(min = MIN_TAP_TARGET_DP.dp)"))
    }

    private companion object {
        const val SECTION_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/SavedScreenHost.kt"
        const val HOST_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/FavouritesListsHost.kt"
    }
}
