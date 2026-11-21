package nl.r1a.grassmaps.tracks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The noise estimators and the height average, apart from the analyser. */
class TrackNoiseTest {
    @Test
    fun aStraightLineHasNoNoise() {
        val line = DoubleArray(50) { 300.0 + 0.25 * it }

        assertEquals(0.0, TrackNoise.seriesNoise(line), 1e-9)
    }

    @Test
    fun aConstantCurvatureHasNoNoise() {
        // A parabola is exactly what the five-point quadratic fits, so a bend is
        // not mistaken for a wander however tight it is.
        val bend = DoubleArray(50) { 300.0 + 0.01 * it * it }

        assertEquals(0.0, TrackNoise.seriesNoise(bend), 1e-9)
    }

    @Test
    fun aWhiteWanderOfOneMetreReadsAboutOneMetre() {
        // Alternating between zero and one metre is the noisiest series a sample
        // can carry against its neighbours, and it must not read as smooth.
        val wander = DoubleArray(61) { if (it % 2 == 0) 0.0 else 1.0 }

        assertEquals(1.0, TrackNoise.seriesNoise(wander), 0.1)
    }

    @Test
    fun oneSpikeDoesNotMoveTheScale() {
        val flat = DoubleArray(61) { 300.0 }
        flat[30] = 400.0

        assertTrue("a single spike is not a noise scale, was ${TrackNoise.seriesNoise(flat)}", TrackNoise.seriesNoise(flat) < 0.1)
    }

    @Test
    fun aSeriesShorterThanTheFitHasNoScale() {
        assertEquals(0.0, TrackNoise.seriesNoise(doubleArrayOf(300.0, 305.0, 301.0, 304.0)), 0.0)
    }

    @Test
    fun theAverageOfARampKeepsItsSlope() {
        val ramp = DoubleArray(21) { 100.0 + it.toDouble() }

        val smoothed = TrackNoise.smoothed(ramp, halfWindow = 3)

        for (index in 3..17) {
            assertEquals("sample $index of a ramp", ramp[index], smoothed[index], 1e-9)
        }
    }

    @Test
    fun theAverageShrinksAtTheEndsInsteadOfPinningThem() {
        val ramp = DoubleArray(21) { it.toDouble() }

        val smoothed = TrackNoise.smoothed(ramp, halfWindow = 3)

        // A one-sided average of a rising line sits below it at the start.
        assertEquals(1.5, smoothed.first(), 1e-9)
        assertEquals(18.5, smoothed.last(), 1e-9)
    }

    @Test
    fun theAverageWindowIsMeasuredInTimeNotInSamples() {
        assertEquals(15, TrackNoise.halfWindowSamples(windowMillis = 120_000, intervalMillis = 4_000))
        assertEquals(30, TrackNoise.halfWindowSamples(windowMillis = 120_000, intervalMillis = 2_000))
        assertEquals(1, TrackNoise.halfWindowSamples(windowMillis = 120_000, intervalMillis = 600_000))
        assertEquals(0, TrackNoise.halfWindowSamples(windowMillis = 120_000, intervalMillis = 0))
    }

    @Test
    fun theAverageIsIdentityWithoutAWindow() {
        val values = doubleArrayOf(1.0, 5.0, 2.0)

        assertTrue(TrackNoise.smoothed(values, halfWindow = 0).contentEquals(values))
    }
}
