package nl.r1a.grassmaps.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The round-5 navigation bar is at least 58 high and fully round at that
 * height, so its corner is 29. The height is a minimum: at font scale 2.0 the
 * trip summary is taller than 58 dp and an exact height cut it off (blind
 * review 9). Rendered bounds are checked on the AVD, not by this test.
 */
class NavigationBarMetricsTest {
    private val source = File(HOST_FILE).readText()
    private val bar = source.substringAfter("private fun Bar(").substringBefore("\n}\n")

    @Test
    fun theBarIsRoundFiveMinimumHeightAndFullyRoundAtThatHeight() {
        assertEquals(58, NAV_BAR_MIN_HEIGHT_DP)
        assertEquals(29, NAV_BAR_CORNER_DP)
        assertEquals("the bar must be fully round", NAV_BAR_MIN_HEIGHT_DP / 2, NAV_BAR_CORNER_DP)
    }

    @Test
    fun theBarGrowsWithItsContent() {
        assertTrue(
            "the bar lost its minimum height",
            bar.contains(".heightIn(min = NAV_BAR_MIN_HEIGHT_DP.dp)"),
        )
        assertFalse("an exact bar height clips the trip summary at large font scales", Regex("""\.height\(""").containsMatchIn(bar))
    }

    @Test
    fun theBarPillsHaveAMinimumThatGrowsWithTheirLabel() {
        assertEquals(48, NAV_TARGET_MIN_DP)
        val endPill = source.substringAfter("private fun EndPill(").substringBefore("\n}\n")
        assertTrue(endPill.contains(".heightIn(min = NAV_TARGET_MIN_DP.dp)"))
        assertFalse("an exact End height clips the label", endPill.contains(".height("))
        assertTrue(bar.contains("modifier = Modifier.fillMaxWidth().heightIn(min = DONE_HEIGHT_DP.dp)"))
        assertTrue(bar.contains("modifier = Modifier.weight(1f).heightIn(min = NAV_TARGET_MIN_DP.dp)"))
    }

    @Test
    fun theRecentreButtonSitsAboveTheMeasuredBarNotAboveAFixedHeight() {
        assertFalse(
            "a fixed bar height under the recentre button overlaps a taller bar",
            Regex("""NAV_BAR_(MIN_)?HEIGHT_DP\s*\+""").containsMatchIn(source),
        )
    }

    @Test
    fun theReadoutChipsAndAlertChipsGrowWithTheirText() {
        val glass = source.substringAfter("private fun glassChip(").substringBefore("\n\n")
        assertTrue(glass.contains(".heightIn(min = CHIP_HEIGHT_DP.dp)"))
        assertFalse(glass.contains(".height("))
        val alerts = source.substringAfter("private fun AlertChips(").substringBefore("\n}\n")
        assertTrue(alerts.contains(".heightIn(min = NAV_TARGET_MIN_DP.dp)"))
        assertFalse(alerts.contains(".height("))
    }

    private companion object {
        const val HOST_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/NavigationScreenHost.kt"
    }
}
