package nl.r1a.grassmaps.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Final blind review P2-1: the colour swatches were 20 dp squares and Create
 * list had no minimum height. The colours and commands stay as they are; the
 * clickable boxes gain the shell's 48 dp minimum. Mappers are Composables a JVM
 * test cannot render, so pin the source shape and the preset values.
 */
class FavouritesListsTapTargetTest {
    private val source = File(HOST_FILE).readText()

    @Test
    fun colourSwatchesAreFullTouchTargets() {
        val chooser = source.substringAfter("private fun ColourChooser(").substringBefore("\n}\n")

        assertTrue("the swatch size is not the tap target", chooser.contains(".size(CHIP_SIZE_DP.dp)"))
        assertTrue("the swatch is still under 48 dp", source.contains("private const val CHIP_SIZE_DP = 48"))
        assertTrue("the colour command changed", chooser.contains("onChoose(preset)"))
        assertEquals(6, PRESET_COLOURS.size)
    }

    @Test
    fun createListIsA48DpTargetWithItsOriginalCommand() {
        val action = source.substringAfter("private fun ActionButton(").substringBefore("\n}\n")

        assertTrue(
            "ActionButton has no minimum width",
            action.contains(".widthIn(min = MIN_TAP_TARGET_DP.dp)"),
        )
        assertTrue(
            "ActionButton has no minimum height",
            action.contains(".heightIn(min = MIN_TAP_TARGET_DP.dp)"),
        )
        assertTrue(
            "the shell tap target is not 48 dp",
            source.contains("private const val MIN_TAP_TARGET_DP = 48"),
        )
        assertTrue(
            "Create list lost its command",
            source.contains("ActionButton(text = strings.resolve(CREATE_KEY), onClick = onCreate)"),
        )
    }

    @Test
    fun glyphActionsAreFullTouchTargetsToo() {
        val glyph = source.substringAfter("private fun GlyphButton(").substringBefore("\n}\n")

        assertTrue("GlyphButton has no minimum width", glyph.contains(".widthIn(min = MIN_TAP_TARGET_DP.dp)"))
        assertTrue("GlyphButton has no minimum height", glyph.contains(".heightIn(min = MIN_TAP_TARGET_DP.dp)"))
    }

    private companion object {
        const val HOST_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/FavouritesListsHost.kt"
    }
}
