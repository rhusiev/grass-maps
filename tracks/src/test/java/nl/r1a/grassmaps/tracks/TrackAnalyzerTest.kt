package nl.r1a.grassmaps.tracks

import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.Track
import nl.r1a.grassmaps.plugin.api.model.TrackPoint
import nl.r1a.grassmaps.plugin.api.model.TrackSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TrackAnalyzerTest {
    @Test
    fun rawElevationDeltasGiveAscentAndDescent() {
        val analysis =
            TrackAnalyzer().analyze(
                track(
                    points(
                        point(49.84, 24.03, 100.0),
                        point(49.84, 24.03, 110.0),
                        point(49.84, 24.03, 105.0),
                        point(49.84, 24.03, 120.0),
                    ),
                ),
            )

        assertEquals(4, analysis.pointCount)
        assertEquals(0.0, analysis.distanceMeters, 1e-9)
        assertEquals(25.0, analysis.ascentMeters, 1e-9)
        assertEquals(5.0, analysis.descentMeters, 1e-9)
        assertEquals(100.0, analysis.minElevationMeters ?: 0.0, 0.0)
        assertEquals(120.0, analysis.maxElevationMeters ?: 0.0, 0.0)
    }

    @Test
    fun aSegmentBoundaryIsNotASample() {
        val analysis =
            TrackAnalyzer().analyze(
                Track(
                    segments =
                        listOf(
                            TrackSegment(
                                points(
                                    point(49.84, 24.03, 100.0),
                                    point(49.841, 24.03, 110.0),
                                ),
                            ),
                            TrackSegment(
                                points(
                                    point(49.842, 24.03, 300.0),
                                    point(49.843, 24.03, 290.0),
                                ),
                            ),
                        ),
                ),
            )

        assertEquals(4, analysis.pointCount)
        assertEquals(10.0, analysis.ascentMeters, 1e-9)
        assertEquals(10.0, analysis.descentMeters, 1e-9)
        assertEquals(100.0, analysis.minElevationMeters ?: 0.0, 0.0)
        assertEquals(300.0, analysis.maxElevationMeters ?: 0.0, 0.0)
    }

    @Test
    fun distanceUsesHaversineGroundDistance() {
        val analysis =
            TrackAnalyzer().analyze(
                track(
                    points(
                        point(49.0, 24.0, null),
                        point(50.0, 24.0, null),
                    ),
                ),
            )

        assertEquals(ONE_DEGREE_LATITUDE_METERS, analysis.distanceMeters, 1.0)
        assertNull(analysis.minElevationMeters)
        assertNull(analysis.maxElevationMeters)
        assertEquals(0.0, analysis.ascentMeters, 0.0)
        assertEquals(0.0, analysis.descentMeters, 0.0)
    }

    @Test
    fun aPointWithoutElevationBreaksTheDeltaWithoutBreakingTheDistance() {
        val analysis =
            TrackAnalyzer().analyze(
                track(
                    points(
                        point(49.84, 24.03, 100.0),
                        point(49.84001, 24.03, null),
                        point(49.84002, 24.03, 120.0),
                    ),
                ),
            )

        assertEquals(3, analysis.pointCount)
        assertEquals(0.0, analysis.ascentMeters, 1e-9)
        assertEquals(0.0, analysis.descentMeters, 1e-9)
        assertEquals(100.0, analysis.minElevationMeters ?: 0.0, 0.0)
        assertEquals(120.0, analysis.maxElevationMeters ?: 0.0, 0.0)
        assertEquals(TWO_STEPS_METERS, analysis.distanceMeters, 0.01)
    }

    @Test
    fun aTimedTrackGetsItsDurationMovingTimeAndSpeeds() {
        val analysis =
            TrackAnalyzer().analyze(
                track(
                    points(
                        timedPoint(49.84, 24.03, 100.0, 0L),
                        timedPoint(49.8401, 24.03005, 101.0, 1_000L),
                        timedPoint(49.8402, 24.03010, 102.0, 2_000L),
                    ),
                ),
            )

        assertEquals(2_000L, analysis.durationMillis ?: -1L)
        assertEquals(2_000L, analysis.movingTimeMillis ?: -1L)
        assertEquals(
            analysis.distanceMeters / 2.0,
            analysis.averageSpeedMetersPerSecond ?: 0.0,
            1e-9,
        )
        assertEquals(11.683, analysis.maxSpeedMetersPerSecond ?: 0.0, 1e-3)
    }

    @Test
    fun aPauseGapAddsToDurationButNotToMovingTimeOrMaxSpeed() {
        val analysis =
            TrackAnalyzer().analyze(
                track(
                    points(
                        timedPoint(49.84, 24.03, 100.0, 0L),
                        timedPoint(49.8401, 24.03005, 101.0, 1_000L),
                        timedPoint(49.86, 24.06, 102.0, 200_000L),
                    ),
                ),
            )

        assertEquals(200_000L, analysis.durationMillis ?: -1L)
        assertEquals(1_000L, analysis.movingTimeMillis ?: -1L)
        assertEquals(
            analysis.distanceMeters,
            analysis.averageSpeedMetersPerSecond ?: 0.0,
            1e-6,
        )
        // The second step would be about 14.8 m/s if the pause were a sample.
        assertEquals(11.683, analysis.maxSpeedMetersPerSecond ?: 0.0, 1e-3)
    }

    @Test
    fun aSegmentBoundaryIsAPauseForTimeAndSpeedButNotForDistance() {
        val analysis =
            TrackAnalyzer().analyze(
                Track(
                    segments =
                        listOf(
                            TrackSegment(
                                points(
                                    timedPoint(49.84, 24.03, 100.0, 0L),
                                    timedPoint(49.8401, 24.03005, 101.0, 1_000L),
                                ),
                            ),
                            TrackSegment(
                                points(
                                    timedPoint(49.85, 24.04, 102.0, 5_000L),
                                    timedPoint(49.8501, 24.04005, 103.0, 6_000L),
                                ),
                            ),
                        ),
                ),
            )

        assertEquals(6_000L, analysis.durationMillis ?: -1L)
        assertEquals(2_000L, analysis.movingTimeMillis ?: -1L)
        assertEquals(11.683, analysis.maxSpeedMetersPerSecond ?: 0.0, 1e-3)
        assertEquals(TWO_GENERATOR_STEPS_METERS, analysis.distanceMeters, 0.01)
    }

    @Test
    fun aStepSlowerThanTheMovingSpeedCountsAsStandingStill() {
        val analysis =
            TrackAnalyzer().analyze(
                track(
                    points(
                        timedPoint(49.84, 24.03, 100.0, 0L),
                        timedPoint(49.8400005, 24.0300002, 100.0, 10_000L),
                    ),
                ),
            )

        assertEquals(10_000L, analysis.durationMillis ?: -1L)
        assertEquals(0L, analysis.movingTimeMillis ?: -1L)
        assertNull(analysis.averageSpeedMetersPerSecond)
        assertEquals(0.005742, analysis.maxSpeedMetersPerSecond ?: 0.0, 1e-5)
    }

    @Test
    fun thePauseThresholdIsThePolicyNotAFixedNumber() {
        val points =
            track(
                points(
                    timedPoint(49.84, 24.03, 100.0, 0L),
                    timedPoint(49.8401, 24.03005, 101.0, 2_000L),
                ),
            )
        val strict = TrackAnalyzer(TrackStatisticsPolicy(pauseGapMillis = 1_500))

        assertEquals(2_000L, TrackAnalyzer().analyze(points).movingTimeMillis ?: -1L)
        assertEquals(0L, strict.analyze(points).movingTimeMillis ?: -1L)
        assertNull(strict.analyze(points).maxSpeedMetersPerSecond)
    }

    /**
     * A gap of ten sampling intervals is a loss of fixes: the chord across it is
     * the best estimate of the path, but the walker was not observed, so the time
     * does not count as moving.
     */
    @Test
    fun aFixLossGapKeepsItsDistanceButGivesNoMovingTime() {
        val walk = fixLossWalk()

        val analysis = TrackAnalyzer().analyze(walk)

        assertEquals(19 * STEP_METERS, analysis.distanceMeters, 1.0)
        assertEquals(56_000L, analysis.durationMillis ?: -1L)
        assertEquals(36_000L, analysis.movingTimeMillis ?: -1L)
    }

    @Test
    fun theFixLossFactorIsThePolicyNotAFixedNumber() {
        val walk = fixLossWalk()
        val tolerant = TrackAnalyzer(TrackStatisticsPolicy(fixLossGapFactor = 20.0))

        assertEquals(
            56_000L,
            tolerant.analyze(walk).movingTimeMillis ?: -1L,
        )
    }

    /**
     * The moving test is the run's speed, not the step's: one step that a jittered
     * fix pair makes 0.2 m long still belongs to a walk that moved 1.35 m/s.
     */
    @Test
    fun aStepTheWanderShortenedStillCountsAsMoving() {
        val walk = straightWalk(points = 20, stepMeters = STEP_METERS, pauseAt = 10)

        val analysis = TrackAnalyzer().analyze(walk)

        assertEquals(38_000L, analysis.movingTimeMillis ?: -1L)
        assertEquals(38_000L, analysis.durationMillis ?: -1L)
    }

    @Test
    fun noisyHeightsLoseTheInventedDescent() {
        // 1 200 two-second samples are 3.2 km of climb, so the average window's
        // shrinking ends cost a few per cent of the rise and the wander's invented
        // 40 % is what this measures.
        val walk = climbWithHeightNoise(points = 1_200, riseMeters = 100.0, noiseMeters = 2.0)

        val analysis = TrackAnalyzer().analyze(walk)

        assertTrue(
            "a 100 m climb stays a climb, was ${analysis.ascentMeters}",
            abs(analysis.ascentMeters - 100.0) <= 8.0,
        )
        assertTrue(
            "a monotone climb has no descent, was ${analysis.descentMeters}",
            analysis.descentMeters < 5.0,
        )
    }

    @Test
    fun aCleanProfileKeepsItsHeightSumExactly() {
        val walk = climbWithHeightNoise(points = 1_200, riseMeters = 100.0, noiseMeters = 0.0)

        val analysis = TrackAnalyzer().analyze(walk)

        assertEquals(100.0, analysis.ascentMeters, 1e-9)
        assertEquals(0.0, analysis.descentMeters, 1e-9)
    }

    @Test
    fun theHeightAverageWindowIsMeasuredInTimeNotInSamples() {
        val shortSteps = climbWithHeightNoise(points = 120, riseMeters = 60.0, noiseMeters = 2.0, stepMillis = 10_000)

        val analysis = TrackAnalyzer().analyze(shortSteps)

        assertEquals(120, analysis.pointCount)
        assertTrue(
            "a ten-second track still gains its climb, was ${analysis.ascentMeters}",
            abs(analysis.ascentMeters - 60.0) <= 12.0,
        )
    }

    @Test
    fun oneTimestampedPointHasNoTimeSpan() {
        val analysis =
            TrackAnalyzer().analyze(
                track(points(timedPoint(49.84, 24.03, 100.0, 0L))),
            )

        assertNull(analysis.durationMillis)
        assertNull(analysis.movingTimeMillis)
        assertNull(analysis.averageSpeedMetersPerSecond)
        assertNull(analysis.maxSpeedMetersPerSecond)
    }

    @Test
    fun twoPointsAtTheSameTimeGiveAZeroSpanAndNoSpeed() {
        val analysis =
            TrackAnalyzer().analyze(
                track(
                    points(
                        timedPoint(49.84, 24.03, 100.0, 0L),
                        timedPoint(49.8401, 24.03005, 101.0, 0L),
                    ),
                ),
            )

        assertEquals(0L, analysis.durationMillis ?: -1L)
        assertEquals(0L, analysis.movingTimeMillis ?: -1L)
        assertNull(analysis.averageSpeedMetersPerSecond)
        assertNull(analysis.maxSpeedMetersPerSecond)
    }

    @Test
    fun timestampsThatGoBackwardsGiveNoTimeSpan() {
        val analysis =
            TrackAnalyzer().analyze(
                track(
                    points(
                        timedPoint(49.84, 24.03, 100.0, 0L),
                        timedPoint(49.8401, 24.03005, 101.0, -1_000L),
                    ),
                ),
            )

        assertNull(analysis.durationMillis)
        assertNull(analysis.movingTimeMillis)
        assertNull(analysis.maxSpeedMetersPerSecond)
    }

    @Test
    fun aPointWithoutATimestampBreaksTheStepWithoutBreakingTheSpan() {
        val analysis =
            TrackAnalyzer().analyze(
                track(
                    points(
                        timedPoint(49.84, 24.03, 100.0, 0L),
                        point(49.8401, 24.03005, 101.0),
                        timedPoint(49.8402, 24.03010, 102.0, 2_000L),
                    ),
                ),
            )

        assertEquals(2_000L, analysis.durationMillis ?: -1L)
        assertEquals(0L, analysis.movingTimeMillis ?: -1L)
        assertNull(analysis.maxSpeedMetersPerSecond)
        assertEquals(TWO_GENERATOR_STEPS_METERS, analysis.distanceMeters, 0.01)
    }

    @Test
    fun aTrackWithoutTimestampsHasNoTiming() {
        val analysis =
            TrackAnalyzer().analyze(
                track(
                    points(
                        point(49.84, 24.03, 100.0),
                        point(49.8401, 24.03005, 101.0),
                    ),
                ),
            )

        assertNull(analysis.durationMillis)
        assertNull(analysis.movingTimeMillis)
        assertNull(analysis.averageSpeedMetersPerSecond)
        assertNull(analysis.maxSpeedMetersPerSecond)
    }

    private fun track(points: List<TrackPoint>): Track = Track(segments = listOf(TrackSegment(points)))

    private fun points(vararg points: TrackPoint): List<TrackPoint> = points.toList()

    /** Twenty points two seconds apart, with a twenty-second loss of fixes after the tenth. */
    private fun fixLossWalk(): Track {
        val steps = 20
        val trackPoints =
            (0 until steps).map { index ->
                val timeMillis =
                    if (index <= 9) {
                        index * STEP_MILLIS
                    } else {
                        9 * STEP_MILLIS + FIX_LOSS_MILLIS + (index - 10) * STEP_MILLIS
                    }
                timedPoint(latitudeAt(index * STEP_METERS), BASE_LONGITUDE, 300.0, timeMillis)
            }
        return track(trackPoints)
    }

    /**
     * Twenty points on a meridian, one of them [SHORT_STEP_METERS] behind the fix
     * before it - a wander that makes one step far too slow to be moving on its
     * own.
     */
    private fun straightWalk(
        points: Int,
        stepMeters: Double,
        pauseAt: Int,
    ): Track {
        val trackPoints =
            (0 until points).map { index ->
                val north =
                    if (index == pauseAt) {
                        (index - 1) * stepMeters - SHORT_STEP_METERS
                    } else {
                        index * stepMeters
                    }
                timedPoint(latitudeAt(north), BASE_LONGITUDE, 300.0, index * STEP_MILLIS)
            }
        return track(trackPoints)
    }

    /** A monotone climb with a uniform height wander from a fixed seed. */
    private fun climbWithHeightNoise(
        points: Int,
        riseMeters: Double,
        noiseMeters: Double,
        stepMillis: Long = STEP_MILLIS,
    ): Track {
        val wander = Wander(ELEVATION_SEED)
        val trackPoints =
            (0 until points).map { index ->
                val edge = index == 0 || index == points - 1
                val noise = if (edge) 0.0 else wander.meters(noiseMeters)
                val elevation = 300.0 + riseMeters * index / (points - 1) + noise
                timedPoint(latitudeAt(index * STEP_METERS), BASE_LONGITUDE, elevation, index * stepMillis)
            }
        return track(trackPoints)
    }

    private fun latitudeAt(northMeters: Double): Double = BASE_LATITUDE + northMeters / ONE_DEGREE_LATITUDE_METERS

    private fun point(
        latitude: Double,
        longitude: Double,
        elevation: Double?,
    ): TrackPoint = TrackPoint(GeoPoint(latitude, longitude), elevation)

    private fun timedPoint(
        latitude: Double,
        longitude: Double,
        elevation: Double?,
        timeMillis: Long,
    ): TrackPoint = TrackPoint(GeoPoint(latitude, longitude), elevation, timeMillis)

    /** Uniform in `[-amplitude, amplitude]` from a fixed seed, so a run is reproducible. */
    private class Wander(
        seed: Int,
    ) {
        private var state: Int = seed

        fun meters(amplitude: Double): Double {
            state = state * LCG_MULTIPLIER + LCG_INCREMENT
            val unit = ((state ushr 16) and LCG_MASK) / LCG_HALF_RANGE - 1.0
            return unit * amplitude
        }
    }

    private companion object {
        /** A degree of latitude on the mean-radius sphere. */
        const val ONE_DEGREE_LATITUDE_METERS: Double = 111_194.93
        const val BASE_LATITUDE: Double = 49.84
        const val BASE_LONGITUDE: Double = 24.03
        const val STEP_METERS: Double = 2.7
        const val STEP_MILLIS: Long = 2_000
        const val FIX_LOSS_MILLIS: Long = 20_000
        const val SHORT_STEP_METERS: Double = 0.2
        const val ELEVATION_SEED: Int = 4_242
        const val LCG_MULTIPLIER: Int = 1_103_515_245
        const val LCG_INCREMENT: Int = 12_345
        const val LCG_MASK: Int = 0x7FFF
        const val LCG_HALF_RANGE: Double = 16_384.0
        const val TWO_STEPS_METERS: Double = 2.2239
        const val TWO_GENERATOR_STEPS_METERS: Double = 23.3665
    }
}
