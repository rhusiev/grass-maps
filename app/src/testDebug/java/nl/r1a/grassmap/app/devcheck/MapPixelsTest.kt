package nl.r1a.grassmap.app.devcheck

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

class MapPixelsTest {
    @Test
    fun `a transparent area is blank`() {
        val area = MapPixels.check(WIDTH_PX, HEIGHT_PX) { _, _ -> TRANSPARENT }

        assertTrue(area.isBlank)
        assertEquals(1.0, area.dominantShare, 0.0)
    }

    @Test
    fun `a flat fill of the empty map style is blank`() {
        assertTrue(MapPixels.check(WIDTH_PX, HEIGHT_PX) { _, _ -> BEIGE }.isBlank)
    }

    @Test
    fun `an area with drawn features is not blank`() {
        val area = MapPixels.check(WIDTH_PX, HEIGHT_PX) { x, _ -> if (x % ROAD_PERIOD_PX < 8) ROAD else BEIGE }

        assertFalse(area.isBlank)
    }
}
