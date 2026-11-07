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

    private fun track(points: List<TrackPoint>): Track = Track(segments = listOf(TrackSegment(points)))

    private fun points(vararg points: TrackPoint): List<TrackPoint> = points.toList()

    private fun point(
        latitude: Double,
        longitude: Double,
        elevation: Double?,
    ): TrackPoint = TrackPoint(GeoPoint(latitude, longitude), elevation)

    private companion object {
        /** A degree of latitude on the mean-radius sphere. */
        const val ONE_DEGREE_LATITUDE_METERS: Double = 111_194.93
        const val TWO_STEPS_METERS: Double = 2.2239
    }
}
