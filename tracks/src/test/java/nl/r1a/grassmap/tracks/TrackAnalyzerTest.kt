package nl.r1a.grassmap.tracks

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.Track
import nl.r1a.grassmap.plugin.api.model.TrackPoint
import nl.r1a.grassmap.plugin.api.model.TrackSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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

    private companion object {
        /** A degree of latitude on the mean-radius sphere. */
        const val ONE_DEGREE_LATITUDE_METERS: Double = 111_194.93
        const val TWO_STEPS_METERS: Double = 2.2239
        const val TWO_GENERATOR_STEPS_METERS: Double = 23.3665
    }
}
