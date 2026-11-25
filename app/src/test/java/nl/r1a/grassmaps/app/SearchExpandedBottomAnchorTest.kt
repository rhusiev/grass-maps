package nl.r1a.grassmaps.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-0612 final-review defect: the host gave the expanded search card top and
 * status-bar padding, so the card and its two privacy/region actions opened at
 * the top of the screen. The card must take the bottom navigation clearance so
 * the shell can anchor it above the keyboard. Source-checked: [SearchOverlay]
 * is a private Composable and a JVM test cannot render its Modifier.
 */
class SearchExpandedBottomAnchorTest {
    private val source = File(HOST_FILE).readText()

    @Test
    fun theExpandedCardNoLongerUsesTopStatusBarPadding() {
        val expanded = source.substringAfter("cardModifier =").substringBefore("onlineSearchEnabled =")
        assertFalse(
            "the expanded card still opens below the status bar",
            expanded.contains(".statusBarsPadding()"),
        )
        assertTrue(
            "the expanded card lost its bottom navigation clearance",
            expanded.contains(".navigationBarsPadding()"),
        )
    }

    @Test
    fun theShowOnMapChipIsWiredToTheMap() {
        assertTrue(
            "the Show-on-map chip has no map callback",
            source.contains("onShowOnMap = {"),
        )
    }

    private companion object {
        const val HOST_FILE = "src/main/java/nl/r1a/grassmaps/app/MapScreen.kt"
    }
}
