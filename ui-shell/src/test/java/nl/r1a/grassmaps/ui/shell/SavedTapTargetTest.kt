package nl.r1a.grassmaps.ui.shell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Final blind review P2-1: the Saved search and sort icons measured 35x48 dp
 * because TapIcon set only a minimum height. The commands and labels stay as
 * they are; only the touch target gains the shell's minimum width. Mappers are
 * Composables a JVM test cannot render, so pin the source shape.
 */
class SavedTapTargetTest {
    private val source = File(HOST_FILE).readText()

    @Test
    fun tapIconsHaveA48DpMinimumInBothDimensions() {
        val tapIcon = source.substringAfter("private fun TapIcon(").substringBefore("\n}\n")

        assertTrue(
            "TapIcon has no minimum width",
            tapIcon.contains(".widthIn(min = MIN_TAP_TARGET_DP.dp)"),
        )
        assertTrue(
            "TapIcon has no minimum height",
            tapIcon.contains(".heightIn(min = MIN_TAP_TARGET_DP.dp)"),
        )
        assertTrue(
            "the shell tap target is not 48 dp",
            source.contains("private const val MIN_TAP_TARGET_DP = 48"),
        )
    }

    @Test
    fun searchAndSortKeepTheirGlyphsLabelsAndCommands() {
        val bar = source.substringAfter("private fun SavedBar(").substringBefore("private fun TapIcon(")

        assertTrue("search lost its TapIcon", bar.contains("TapIcon(glyph = SEARCH_GLYPH"))
        assertTrue("sort lost its TapIcon", bar.contains("TapIcon(glyph = SORT_GLYPH"))
        assertTrue("the search command changed", bar.contains("onToggleSearch"))
        assertTrue("the sort command changed", bar.contains("onSort"))
        assertTrue("the spoken labels are no longer passed", bar.contains("label = action.label"))
    }

    private companion object {
        const val HOST_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/SavedScreenHost.kt"
    }
}
