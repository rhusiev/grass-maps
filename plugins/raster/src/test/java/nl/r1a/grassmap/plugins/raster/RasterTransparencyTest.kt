package nl.r1a.grassmap.plugins.raster

import org.junit.Assert.assertEquals
import org.junit.Test

/** The percent the control shows and the style value the renderer applies. */
class RasterTransparencyTest {
    @Test
    fun fortyPercentIsZeroPointFour() {
        assertEquals(0.4f, RasterTransparency.opacityOf(40), 0f)
    }

    @Test
    fun theEndsOfTheRangeAreFullAndNoOpacity() {
        assertEquals(1f, RasterTransparency.opacityOf(100), 0f)
        assertEquals(0f, RasterTransparency.opacityOf(0), 0f)
    }

    @Test
    fun aValueOutsideTheRangeIsClamped() {
        assertEquals(1f, RasterTransparency.opacityOf(140), 0f)
        assertEquals(0f, RasterTransparency.opacityOf(-20), 0f)
    }
}
