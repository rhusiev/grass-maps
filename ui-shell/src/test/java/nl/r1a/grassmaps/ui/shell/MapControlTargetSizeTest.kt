package nl.r1a.grassmaps.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-0612 final-review defect: the round rail faces drew at 42 dp inside a 48 dp
 * box, the slider pill was 44 dp wide and its drag track was only 12 dp.
 * Every interactive face and gesture area must be at least 48 dp. Pure sizes
 * and source shape are the closest a JVM test can get to a rendered target.
 */
class MapControlTargetSizeTest {
    @Test
    fun everyRoundFaceIsAFullTouchTarget() {
        assertEquals(48, MapControlMetrics.TAP_TARGET_DP)
        assertEquals(
            "the drawn face is smaller than its tap target",
            MapControlMetrics.TAP_TARGET_DP,
            MapControlMetrics.FAB_DP,
        )
        assertTrue("the face is under 48 dp", MapControlMetrics.FAB_DP >= 48)
    }

    @Test
    fun theSliderFitsThreeFullWidthRows() {
        assertEquals(48, MapControlMetrics.SLIDER_WIDTH_DP)
        assertEquals(144, MapControlMetrics.SLIDER_HEIGHT_DP)
        assertEquals(
            "the pill cannot fit two end targets and a track",
            MapControlMetrics.TAP_TARGET_DP * 3,
            MapControlMetrics.SLIDER_HEIGHT_DP,
        )
    }

    @Test
    fun theDragAreaSpansTheWholePill() {
        val slider = File(SLIDER_FILE).readText()
        val track = slider.substringAfter("BoxWithConstraints(").substringBefore("contentAlignment =")
        assertTrue(
            "the drag track is narrower than the pill",
            track.contains(".fillMaxWidth()"),
        )
        assertTrue(
            "the track lost its gesture area",
            track.contains(".pointerInput(spec)"),
        )
    }

    @Test
    fun theQuickAccessButtonIsAFullTarget() {
        val bar = File(BAR_FILE).readText()
        val expression =
            Regex("""private const val QUICK_ACCESS_SIZE_DP = ([^\n]+)""")
                .find(bar)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?: throw AssertionError("the quick-access size is not a named constant")
        val size =
            if (expression == "MapControlMetrics.TAP_TARGET_DP") {
                MapControlMetrics.TAP_TARGET_DP
            } else {
                expression.toInt()
            }
        assertTrue("the quick-access button is under 48 dp", size >= 48)
    }

    private companion object {
        const val SLIDER_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/ZoomSliderPill.kt"
        const val BAR_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/MapControlBar.kt"
    }
}
