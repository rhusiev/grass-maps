package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.ui.shell.MapControlMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zoom slider pill and the round stack controls are private Composables,
 * so a JVM test cannot render them or inspect their Modifiers. Pin the pure
 * model they render from and the named sizes instead. This is weaker than a
 * rendered assertion: it cannot catch a visual mistake inside the drawn pill.
 */
class ZoomSliderCompositionTest {
    @Test
    fun theSliderUsesTheCameraCommandsRangeAndNamedStep() {
        assertEquals(MIN_ZOOM.toFloat(), zoomSliderSpec.min, 0f)
        assertEquals(MAX_ZOOM.toFloat(), zoomSliderSpec.max, 0f)
        assertEquals(ZOOM_STEP.toFloat(), zoomSliderSpec.step, 0f)
        assertEquals(1f, zoomSliderSpec.min, 0f)
        assertEquals(20f, zoomSliderSpec.max, 0f)
        assertEquals(1f, zoomSliderSpec.step, 0f)
        assertEquals(20, zoomSliderSpec.count)
        assertEquals(1f, zoomSliderSpec.valueAt(0), 0f)
        assertEquals(20f, zoomSliderSpec.valueAt(zoomSliderSpec.count - 1), 0f)
        assertEquals(zoomSliderSpec.count - 1, zoomSliderSpec.indexOf(20f))
    }

    @Test
    fun thePillAndTheRoundControlsUseTheDesignSizes() {
        assertEquals(44, MapControlMetrics.SLIDER_WIDTH_DP)
        assertEquals(140, MapControlMetrics.SLIDER_HEIGHT_DP)
        assertEquals(42, MapControlMetrics.FAB_DP)
        assertEquals(48, MapControlMetrics.TAP_TARGET_DP)
        assertTrue("a round control must keep a 48 dp tap target", MapControlMetrics.TAP_TARGET_DP >= 48)
        assertTrue("the pill must stay 44 dp wide", MapControlMetrics.SLIDER_WIDTH_DP <= MapControlMetrics.TAP_TARGET_DP)
    }
}
