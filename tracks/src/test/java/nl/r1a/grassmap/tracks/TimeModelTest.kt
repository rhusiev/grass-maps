package nl.r1a.grassmap.tracks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The slope-aware time models (T-0407).
 *
 * Every expected time below is derived by hand from the formula in the model's
 * documentation, not from a run of the implementation. The walk and hike
 * figures use Tobler's curve re-anchored at 5.0 and 4.5 km/h; the bike figures
 * solve the power balance at 81.03 W with m = 95 kg, Crr = 0.010, CdA = 0.45
 * and rho = 1.225.
 */
class TimeModelTest {
    private val estimator = SlopeTimeEstimator()

    @Test
    fun aFlatProfileKeepsTheExistingFlatEstimate() {
        val estimate = estimator.estimate(FLAT_PROFILE, TravelMode.Walk, flatSeconds = WALK_FLAT_SECONDS)

        assertEquals(WALK_FLAT_SECONDS, estimate.seconds, TOLERANCE)
        assertEquals(TimeModelKind.Flat, estimate.model)
    }

    @Test
    fun aMissingProfileKeepsTheExistingFlatEstimate() {
        val estimate = estimator.estimate(ElevationProfile(), TravelMode.Hike, flatSeconds = 800.0)

        assertEquals(800.0, estimate.seconds, TOLERANCE)
        assertEquals(TimeModelKind.Flat, estimate.model)
    }

    @Test
    fun aOneSampleProfileKeepsTheExistingFlatEstimate() {
        val estimate =
            estimator.estimate(
                profile(0.0 to 100.0),
                TravelMode.Bike,
                flatSeconds = 200.0,
            )

        assertEquals(200.0, estimate.seconds, TOLERANCE)
        assertEquals(TimeModelKind.Flat, estimate.model)
    }

    @Test
    fun aGradeUnderTheFlatThresholdKeepsTheExistingFlatEstimate() {
        val barelySloped = profile(0.0 to 100.0, 1_000.0 to 100.5)

        val estimate = estimator.estimate(barelySloped, TravelMode.Walk, flatSeconds = 720.0)

        assertEquals(720.0, estimate.seconds, TOLERANCE)
        assertEquals(TimeModelKind.Flat, estimate.model)
    }

    @Test
    fun aGentleClimbSlowsAWalk() {
        val estimate = estimator.estimate(profile(0.0 to 100.0, 1_000.0 to 130.0), TravelMode.Walk, 720.0)

        assertEquals(799.711639, estimate.seconds, TOLERANCE)
        assertEquals(TimeModelKind.ToblerWalk, estimate.model)
        assertTrue(estimate.seconds > 720.0)
    }

    @Test
    fun aGentleDescentSpeedsAWalk() {
        val estimate = estimator.estimate(profile(0.0 to 100.0, 1_000.0 to 80.0), TravelMode.Walk, 720.0)

        assertEquals(671.323550, estimate.seconds, TOLERANCE)
        assertEquals(TimeModelKind.ToblerWalk, estimate.model)
    }

    @Test
    fun aSteepClimbSlowsAWalkMoreThanAGentleClimb() {
        val gentle = estimator.estimate(profile(0.0 to 100.0, 1_000.0 to 130.0), TravelMode.Walk, 720.0)
        val steep = estimator.estimate(profile(0.0 to 100.0, 1_000.0 to 250.0), TravelMode.Walk, 720.0)

        assertEquals(1_217.130371, steep.seconds, TOLERANCE)
        assertEquals(TimeModelKind.ToblerWalk, steep.model)
        assertTrue(steep.seconds > gentle.seconds)
    }

    @Test
    fun aSteepDescentIsSlowerThanAGentleDescent() {
        val gentle = estimator.estimate(profile(0.0 to 100.0, 1_000.0 to 80.0), TravelMode.Walk, 720.0)
        val steep = estimator.estimate(profile(0.0 to 100.0, 1_000.0 to -50.0), TravelMode.Walk, 720.0)

        assertEquals(857.697276, steep.seconds, TOLERANCE)
        assertEquals(TimeModelKind.ToblerWalk, steep.model)
        assertTrue(steep.seconds > gentle.seconds)
        assertEquals(true, steep.seconds < 1_217.130371)
    }

    @Test
    fun aHikeUsesTheHikeFlatSpeed() {
        val estimate = estimator.estimate(profile(0.0 to 100.0, 1_000.0 to 130.0), TravelMode.Hike, 800.0)

        assertEquals(888.568488, estimate.seconds, TOLERANCE)
        assertEquals(TimeModelKind.ToblerHike, estimate.model)
    }

    @Test
    fun aSteepHikeClimbSlowsMoreThanAGentleOne() {
        val gentle = estimator.estimate(profile(0.0 to 100.0, 1_000.0 to 130.0), TravelMode.Hike, 800.0)
        val steep = estimator.estimate(profile(0.0 to 100.0, 1_000.0 to 250.0), TravelMode.Hike, 800.0)

        assertEquals(1_352.367079, steep.seconds, TOLERANCE)
        assertEquals(TimeModelKind.ToblerHike, steep.model)
        assertTrue(steep.seconds > gentle.seconds)
    }

    @Test
    fun aBikeClimbUsesThePowerModel() {
        val estimate = estimator.estimate(profile(0.0 to 100.0, 1_000.0 to 120.0), TravelMode.Bike, 200.0)

        assertEquals(369.715563, estimate.seconds, TOLERANCE)
        assertEquals(TimeModelKind.BikePower, estimate.model)
    }

    @Test
    fun aGentleBikeDescentIsFasterThanFlat() {
        val estimate = estimator.estimate(profile(0.0 to 100.0, 1_000.0 to 80.0), TravelMode.Bike, 200.0)

        assertEquals(120.255633, estimate.seconds, TOLERANCE)
        assertEquals(TimeModelKind.BikePower, estimate.model)
        assertTrue(estimate.seconds < 200.0)
    }

    @Test
    fun aSteepBikeClimbIsMuchSlower() {
        val estimate = estimator.estimate(profile(0.0 to 100.0, 1_000.0 to 220.0), TravelMode.Bike, 200.0)

        assertEquals(1_485.466306, estimate.seconds, TOLERANCE)
        assertEquals(TimeModelKind.BikePower, estimate.model)
    }

    @Test
    fun aSteepBikeDescentStopsAtTheStatedCap() {
        val estimate = estimator.estimate(profile(0.0 to 100.0, 1_000.0 to -20.0), TravelMode.Bike, 200.0)

        assertEquals(72.0, estimate.seconds, TOLERANCE)
        assertEquals(TimeModelKind.BikePower, estimate.model)
    }

    @Test
    fun aProfileGapDoesNotBreakTheSlopeSum() {
        val withoutGap = profile(0.0 to 100.0, 1_000.0 to 130.0)
        val withGap = profile(0.0 to 100.0, 500.0 to 115.0, 1_000.0 to 130.0)

        val oneStep = estimator.estimate(withoutGap, TravelMode.Walk, 720.0)
        val twoSteps = estimator.estimate(withGap, TravelMode.Walk, 720.0)

        assertEquals(799.711639, oneStep.seconds, TOLERANCE)
        assertEquals(oneStep.seconds, twoSteps.seconds, TOLERANCE)
    }

    @Test
    fun aZeroDistanceStepAddsNothing() {
        val estimate =
            estimator.estimate(
                profile(0.0 to 100.0, 1_000.0 to 130.0, 1_000.0 to 140.0),
                TravelMode.Walk,
                720.0,
            )

        assertEquals(799.711639, estimate.seconds, TOLERANCE)
    }

    @Test
    fun anAbsurdGradeIsClampedAtHalf() {
        val sixtyPercent = profile(0.0 to 0.0, 1_000.0 to 600.0)

        val estimate = estimator.estimate(sixtyPercent, TravelMode.Walk, 720.0)

        assertEquals(4_143.313927, estimate.seconds, TOLERANCE)
    }

    @Test
    fun theFlatHelperUsesEachModesOwnSpeed() {
        assertEquals(720.0, estimator.flatSeconds(1_000.0, TravelMode.Walk), TOLERANCE)
        assertEquals(800.0, estimator.flatSeconds(1_000.0, TravelMode.Hike), TOLERANCE)
        assertEquals(200.0, estimator.flatSeconds(1_000.0, TravelMode.Bike), TOLERANCE)
    }

    @Test
    fun aNegativeFlatEstimateIsNotCarriedThrough() {
        val estimate = estimator.estimate(ElevationProfile(), TravelMode.Walk, flatSeconds = -5.0)

        assertEquals(0.0, estimate.seconds, TOLERANCE)
        assertEquals(TimeModelKind.Flat, estimate.model)
    }

    private fun profile(vararg points: Pair<Double, Double>): ElevationProfile =
        ElevationProfile(
            samples =
                points.map { (distance, elevation) ->
                    ElevationSample(distanceMeters = distance, elevationMeters = elevation)
                },
        )

    private companion object {
        const val TOLERANCE = 1e-6
        const val WALK_FLAT_SECONDS = 720.0

        val FLAT_PROFILE = ElevationProfile(samples = listOf(ElevationSample(0.0, 100.0), ElevationSample(1_000.0, 100.0)))
    }
}
