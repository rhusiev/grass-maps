package nl.r1a.grassmap.plugins.map.controls

import nl.r1a.grassmap.plugin.api.model.UnitSystem
import nl.r1a.grassmap.plugin.api.model.WebMercator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToLong

class ScaleBarTest {
    @Test
    fun theRenderedBarIsWithinTwoPercentOfTheMapScaleAtSeveralZoomsAndLatitudes() {
        for (latitude in LATITUDES) {
            for (zoom in ZOOMS) {
                for (density in DENSITIES) {
                    for (units in UnitSystem.entries) {
                        assertWithinTwoPercent(latitude, zoom, density, units)
                    }
                }
            }
        }
    }

    private fun assertWithinTwoPercent(
        latitude: Double,
        zoom: Double,
        density: Float,
        units: UnitSystem,
    ) {
        val expectedMetersPerDp = WebMercator.metersPerPixel(latitude, zoom)
        val reading =
            requireNotNull(ScaleBar.reading(expectedMetersPerDp, units)) {
                "no reading for $latitude/$zoom/$density/$units"
            }
        // The bar is laid out in dp; Compose snaps it to whole physical pixels,
        // so the effective span is that many pixels converted back to dp.
        val renderedPixels = (reading.widthDp * density).roundToLong()
        assertTrue("bar narrower than one pixel: $reading", renderedPixels > 0)
        val renderedDp = renderedPixels / density.toDouble()

        val impliedMetersPerDp = reading.distanceMeters / renderedDp
        val error = abs(impliedMetersPerDp - expectedMetersPerDp) / expectedMetersPerDp

        assertTrue(
            "scale bar off by ${error * PERCENT}% at $latitude/$zoom/$density/$units: $reading",
            error <= MAX_ERROR_FRACTION,
        )
        assertTrue(reading.widthDp > 0.0 && reading.widthDp <= ScaleBar.MAX_WIDTH_DP)
    }

    @Test
    fun metricUsesMetresBelowAKilometreAndKilometresFromOne() {
        assertEquals(ScaleBarUnit.Metres, readingForMetric(500.0).unit)
        assertEquals(500.0, readingForMetric(500.0).value, 1e-9)
        assertEquals(ScaleBarUnit.Kilometres, readingForMetric(1_000.0).unit)
        assertEquals(1.0, readingForMetric(1_000.0).value, 1e-9)
        assertEquals(ScaleBarUnit.Kilometres, readingForMetric(4_000.0).unit)
        assertEquals(2.0, readingForMetric(4_000.0).value, 1e-9)
    }

    @Test
    fun imperialReadsFeetBelowAMileAndMilesFromOne() {
        val justUnderAMile = readingForImperial(1_600.0)
        assertEquals(ScaleBarUnit.Feet, justUnderAMile.unit)
        assertEquals(5_000.0, justUnderAMile.value, 1e-9)

        val oneMile = readingForImperial(1_609.344)
        assertEquals(ScaleBarUnit.Miles, oneMile.unit)
        assertEquals(1.0, oneMile.value, 1e-9)

        val fourMiles = readingForImperial(6_500.0)
        assertEquals(ScaleBarUnit.Miles, fourMiles.unit)
        assertEquals(2.0, fourMiles.value, 1e-9)
    }

    @Test
    fun switchingTheUnitSystemChangesTheSameScaleReading() {
        val metersPerDp = 2_000.0 / ScaleBar.MAX_WIDTH_DP
        val metric = requireNotNull(ScaleBar.reading(metersPerDp, UnitSystem.Metric))
        val imperial = requireNotNull(ScaleBar.reading(metersPerDp, UnitSystem.Imperial))

        assertEquals(ScaleBarUnit.Kilometres, metric.unit)
        assertEquals(2.0, metric.value, 1e-9)
        assertEquals(ScaleBarUnit.Miles, imperial.unit)
        assertEquals(1.0, imperial.value, 1e-9)
        assertTrue(metric.widthDp > 0.0)
        assertTrue(imperial.widthDp > 0.0)
    }

    @Test
    fun anInvalidScaleHasNoReading() {
        assertNull(ScaleBar.reading(0.0, UnitSystem.Metric))
        assertNull(ScaleBar.reading(-1.0, UnitSystem.Metric))
        assertNull(ScaleBar.reading(Double.NaN, UnitSystem.Metric))
        assertNull(ScaleBar.reading(1.0, UnitSystem.Metric, maxWidthDp = 0.0))
    }

    private fun readingForMetric(maxMeters: Double): ScaleBarReading =
        requireNotNull(ScaleBar.reading(maxMeters / ScaleBar.MAX_WIDTH_DP, UnitSystem.Metric))

    private fun readingForImperial(maxMeters: Double): ScaleBarReading =
        requireNotNull(ScaleBar.reading(maxMeters / ScaleBar.MAX_WIDTH_DP, UnitSystem.Imperial))

    private companion object {
        val LATITUDES = listOf(0.0, 49.84, 60.0, 70.0, 85.0)
        val ZOOMS = listOf(1.0, 8.0, 13.0, 16.0, 20.0)
        val DENSITIES = listOf(1f, 2.625f, 3.5f)
        const val MAX_ERROR_FRACTION = 0.02
        const val PERCENT = 100.0
    }
}
