package nl.r1a.grassmaps.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-0612 final-review defect: the debug panel followed `aboveBottomSheet()`
 * from y 1464 at launch to y 532 with the planner and y 344 with Layers, and
 * covered Search, the credit and Layers. Its anchor must not depend on the
 * reach of an open sheet. Source-checked: the panel is a debug-only Composable.
 */
class MapDebugPanelPlacementTest {
    private val source = File(HOST_FILE).readText()
    private val panel = source.substringAfter("val devToolsPanel = container.devToolsPanel").substringBefore("val setOnlineSearch")

    @Test
    fun thePanelIsAnchoredAwayFromTheBottomControlArea() {
        assertTrue(
            "the panel still sits in the bottom control area",
            panel.contains(".align(Alignment.CenterStart)"),
        )
        assertFalse(
            "the panel still sits at the bottom start",
            panel.contains("Alignment.BottomStart"),
        )
    }

    @Test
    fun anOpenSheetCannotMoveThePanel() {
        assertFalse(
            "the panel follows the bottom sheet reach",
            panel.contains("aboveBottomSheet()"),
        )
        assertFalse(
            "the panel reads the sheet's chrome key",
            panel.contains("BOTTOM_SHEET_KEY"),
        )
    }

    private companion object {
        const val HOST_FILE = "src/main/java/nl/r1a/grassmaps/app/MapScreen.kt"
    }
}
