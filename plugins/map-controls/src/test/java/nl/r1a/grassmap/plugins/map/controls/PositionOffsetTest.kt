package nl.r1a.grassmap.plugins.map.controls

import nl.r1a.grassmap.plugin.api.model.ScreenOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class PositionOffsetTest {
    @Test
    fun theCentreOffsetKeepsTheMarkerAtTheCentre() {
        assertEquals(
            ScreenOffset(0f, 0f),
            PositionOffsetFraction.CENTER.resolve(viewportWidthDp = 1080f, viewportHeightDp = 1920f),
        )
    }

    @Test
    fun thirtyPercentOfTheViewportIsThirtyPercentOfTheOffset() {
        val offset =
            PositionOffsetFraction(y = 0.3f)
                .resolve(viewportWidthDp = 1080f, viewportHeightDp = 1000f)

        assertEquals(ScreenOffset(0f, 300f), offset)
        assertEquals(800f, 500f + offset.y, 0.001f)
    }

    @Test
    fun eachAxisUsesItsOwnViewportDimension() {
        val offset =
            PositionOffsetFraction(x = 0.25f, y = -0.5f)
                .resolve(viewportWidthDp = 400f, viewportHeightDp = 800f)

        assertEquals(ScreenOffset(100f, -400f), offset)
    }
}
