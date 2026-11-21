package nl.r1a.grassmaps.app.devcheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val WIDTH_PX = 1080
private const val HEIGHT_PX = 1920
private const val TRANSPARENT = 0
private const val BEIGE = 0xFFEDEAE3.toInt()
private const val ROAD = 0xFF202020.toInt()
private const val ROAD_PERIOD_PX = 40
private const val CREDIT_ROW_PERIOD_PX = 20
private const val CREDIT_TOP_PX = 600
private const val CREDIT_BOTTOM_PX = 640

class MapPixelsTest {
    @Test
    fun `a transparent area is blank`() {
        val area = MapPixels.check(WIDTH_PX, HEIGHT_PX) { _, _ -> TRANSPARENT }

        assertTrue(area.isBlank)
        assertEquals(1.0, area.dominantShare, 0.0)
        assertEquals(1.0, area.transparentShare, 0.0)
    }

    @Test
    fun `a map surface left transparent under the credit line is blank`() {
        val area =
            MapPixels.check(WIDTH_PX, HEIGHT_PX) { _, y ->
                if (y % CREDIT_ROW_PERIOD_PX == 0 && y >= CREDIT_TOP_PX && y < CREDIT_BOTTOM_PX) BEIGE else TRANSPARENT
            }

        assertTrue(area.isBlank)
        assertTrue(area.transparentShare > 0.5)
        assertTrue(area.dominantShare < 0.995)
    }

    @Test
    fun `a flat fill of the empty map style is blank`() {
        assertTrue(MapPixels.check(WIDTH_PX, HEIGHT_PX) { _, _ -> BEIGE }.isBlank)
    }

    @Test
    fun `an area with drawn features is not blank`() {
        val area = MapPixels.check(WIDTH_PX, HEIGHT_PX) { x, _ -> if (x % ROAD_PERIOD_PX < 8) ROAD else BEIGE }

        assertFalse(area.isBlank)
        assertEquals(0.0, area.transparentShare, 0.0)
    }
}
