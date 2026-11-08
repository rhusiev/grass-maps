package nl.r1a.grassmap.plugin.api.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebMercatorTest {
    @Test
    fun theEquatorScaleIsTheEngineTileDefinition() {
        // 2 * PI * 6378137 / 512, the value MapLibre's getMetersPerPixelAtLatitude uses.
        assertEquals(78_271.516_964, WebMercator.metersPerPixel(latitude = 0.0, zoom = 0.0), 1e-6)
    }

    @Test
    fun everyZoomLevelHalvesTheGroundScale() {
        val atZoom = WebMercator.metersPerPixel(latitude = 0.0, zoom = 0.0)

        assertEquals(atZoom / 2.0, WebMercator.metersPerPixel(latitude = 0.0, zoom = 1.0), 1e-9)
        assertEquals(atZoom / 256.0, WebMercator.metersPerPixel(latitude = 0.0, zoom = 8.0), 1e-9)
    }

    @Test
    fun theLatitudeCosineScalesTheGroundScale() {
        val equator = WebMercator.metersPerPixel(latitude = 0.0, zoom = 10.0)
        val sixty = WebMercator.metersPerPixel(latitude = 60.0, zoom = 10.0)

        assertEquals(equator / 2.0, sixty, 1e-9)
    }

    @Test
    fun lvivAtStreetZoomMatchesTheKnownScale() {
        assertEquals(0.770_251_515_3, WebMercator.metersPerPixel(latitude = 49.84, zoom = 16.0), 1e-9)
        assertEquals(6.162_012_122_5, WebMercator.metersPerPixel(latitude = 49.84, zoom = 13.0), 1e-9)
    }

    @Test
    fun aPoleOrANonFiniteZoomStillReturnsAPositiveValue() {
        for (zoom in listOf(-1.0, 0.0, 22.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            for (latitude in listOf(-90.0, 90.0, Double.NaN)) {
                val scale = WebMercator.metersPerPixel(latitude, zoom)

                assertTrue("scale=$scale for $latitude/$zoom", scale.isFinite())
                assertTrue("scale=$scale for $latitude/$zoom", scale > 0.0)
            }
        }
    }
}
