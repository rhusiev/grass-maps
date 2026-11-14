package nl.r1a.grassmap.tracks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** The elevation noise filter on its own, apart from the track statistics. */
class ElevationFilterTest {
    @Test
    fun aFlatRippleIsNotClimbed() {
        val gain = ElevationFilter.sum(flatRipple(spacingMeters = 5.0, lengthMeters = 1_000.0, amplitudeMeters = 2.0))

        assertTrue("a 2 m ripple must not become a climb, was ${gain.ascentMeters}", gain.ascentMeters < 3.0)
        assertTrue("a 2 m ripple must not become a descent, was ${gain.descentMeters}", gain.descentMeters < 3.0)
    }

    @Test
    fun aRealClimbKeepsItsRise() {
        val gain = ElevationFilter.sum(climb(spacingMeters = 20.0, lengthMeters = 1_000.0, riseMeters = 100.0))

        assertEquals(100.0, gain.ascentMeters, 0.01)
        assertEquals(0.0, gain.descentMeters, 0.01)
    }

    @Test
    fun aRunShorterThanFourStepsIsSummedRaw() {
        val gain =
            ElevationFilter.sum(
                listOf(
                    ElevationStep(0.0, 100.0),
                    ElevationStep(10.0, 110.0),
                    ElevationStep(10.0, 105.0),
                ),
            )

        assertEquals(10.0, gain.ascentMeters, 1e-9)
        assertEquals(5.0, gain.descentMeters, 1e-9)
    }

    /**
     * The threshold is a vertical tolerance in metres. The same ground sampled
     * at 5 m and at 20 m must not get a lower bar, so the two gains stay within
     * one ripple amplitude of each other instead of differing by the point
     * count.
     */
    @Test
    fun theSameClimbAtFiveAndTwentyMetreSpacingStaysWithinTwoMetres() {
        val five = ElevationFilter.sum(climb(spacingMeters = 5.0, lengthMeters = 1_000.0, riseMeters = 100.0))
        val twenty = ElevationFilter.sum(climb(spacingMeters = 20.0, lengthMeters = 1_000.0, riseMeters = 100.0))

        assertEquals(100.0, five.ascentMeters, 2.0)
        assertEquals(100.0, twenty.ascentMeters, 2.0)
        assertTrue(
            "the two spacings must stay within one ripple amplitude",
            abs(five.ascentMeters - twenty.ascentMeters) <= 2.0,
        )
    }

    /** A flat line with a 2 m sine ripple, sampled every [spacingMeters]. */
    private fun flatRipple(
        spacingMeters: Double,
        lengthMeters: Double,
        amplitudeMeters: Double,
    ): List<ElevationStep> {
        val count = (lengthMeters / spacingMeters).toInt()
        return (0..count).map { index ->
            val distance = index * spacingMeters
            ElevationStep(
                distanceMeters = if (index == 0) 0.0 else spacingMeters,
                elevationMeters = 300.0 + amplitudeMeters * sin(2.0 * PI * distance / 100.0),
            )
        }
    }

    /** A [riseMeters] climb with the same 2 m sine ripple, sampled every [spacingMeters]. */
    private fun climb(
        spacingMeters: Double,
        lengthMeters: Double,
        riseMeters: Double,
    ): List<ElevationStep> {
        val count = (lengthMeters / spacingMeters).toInt()
        return (0..count).map { index ->
            val distance = index * spacingMeters
            ElevationStep(
                distanceMeters = if (index == 0) 0.0 else spacingMeters,
                elevationMeters =
                    100.0 + riseMeters * distance / lengthMeters +
                        2.0 * sin(2.0 * PI * distance / 100.0),
            )
        }
    }
}
