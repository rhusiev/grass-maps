package nl.r1a.grassmap.tracks

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.Track
import nl.r1a.grassmap.plugin.api.model.TrackPoint
import nl.r1a.grassmap.plugin.api.model.TrackSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The range and climb model (T-0405): the engine's own sums over its profile,
 * and one synthetic track whose climbs are fixed by hand.
 */
class ElevationSegmentsTest {
    private val segments = ElevationSegments()
    private val profiler = ElevationProfiler()

    @Test
    fun aWholeProfileRangeEqualsTheStatisticsOfTheSameTrack() {
        val track = syntheticTrack()
        val analysis = TrackAnalyzer().analyze(track)
        val profile = profiler.profile(track)

        val range = segments.range(profile, 0.0, profile.distanceMeters)

        assertEquals(analysis.distanceMeters, range?.distanceMeters ?: 0.0, 1e-6)
        assertEquals(analysis.ascentMeters, range?.ascentMeters ?: 0.0, 1e-6)
        assertEquals(analysis.descentMeters, range?.descentMeters ?: 0.0, 1e-6)
        assertEquals(analysis.durationMillis, range?.durationMillis)
        assertEquals(9 * STEP_METERS, range?.distanceMeters ?: 0.0, 1e-6)
        assertEquals(110.0, range?.ascentMeters ?: 0.0, 1e-6)
        assertEquals(15.0, range?.descentMeters ?: 0.0, 1e-6)
        assertEquals(9.492826061561535, range?.averageGradePercent ?: 0.0, 1e-6)
    }

    @Test
    fun aSyntheticTracksClimbsEqualTheExpectedList() {
        val climbs = segments.climbs(profiler.profile(syntheticTrack()))

        assertEquals(2, climbs.size)
        assertEquals(0.0, climbs[0].startMeters, 1e-6)
        assertEquals(333.5852406997292, climbs[0].endMeters, 1e-6)
        assertEquals(60.0, climbs[0].ascentMeters, 1e-6)
        assertEquals(17.986407274537644, climbs[0].averageGradePercent, 1e-6)
        assertEquals(180_000L, climbs[0].durationMillis)
        assertEquals(555.9754011662153, climbs[1].startMeters, 1e-6)
        assertEquals(889.5606418659445, climbs[1].endMeters, 1e-6)
        assertEquals(50.0, climbs[1].ascentMeters, 1e-6)
        assertEquals(14.98867272878137, climbs[1].averageGradePercent, 1e-6)
        assertEquals(180_000L, climbs[1].durationMillis)
    }

    @Test
    fun aRangeBetweenTwoSamplesFollowsTheDrawnLine() {
        val profile = profiler.profile(syntheticTrack())

        val range = segments.range(profile, STEP_METERS / 2, 2.5 * STEP_METERS)

        assertEquals(STEP_METERS / 2, range?.startMeters ?: 0.0, 1e-6)
        assertEquals(2.5 * STEP_METERS, range?.endMeters ?: 0.0, 1e-6)
        assertEquals(2 * STEP_METERS, range?.distanceMeters ?: 0.0, 1e-6)
        assertEquals(40.0, range?.ascentMeters ?: 0.0, 1e-6)
        assertEquals(0.0, range?.descentMeters ?: 0.0, 1e-6)
        assertEquals(17.986407274537644, range?.averageGradePercent ?: 0.0, 1e-6)
        assertEquals(120_000L, range?.durationMillis)
    }

    @Test
    fun aReversedDragGivesTheSameRange() {
        val profile = profiler.profile(syntheticTrack())

        val forward = segments.range(profile, 0.0, 2 * STEP_METERS)
        val backward = segments.range(profile, 2 * STEP_METERS, 0.0)

        assertEquals(forward, backward)
    }

    @Test
    fun aRangeIsClampedToTheProfilesOwnDistance() {
        val profile = profiler.profile(syntheticTrack())

        val range = segments.range(profile, -500.0, 100_000.0)

        assertEquals(0.0, range?.startMeters ?: -1.0, 0.0)
        assertEquals(profile.distanceMeters, range?.endMeters ?: 0.0, 1e-6)
    }

    @Test
    fun aSegmentBoundaryAddsNoGainToARange() {
        val track =
            Track(
                segments =
                    listOf(
                        TrackSegment(
                            listOf(
                                TrackPoint(GeoPoint(49.8400, 24.0300), 100.0),
                                TrackPoint(GeoPoint(49.8410, 24.0300), 110.0),
                            ),
                        ),
                        TrackSegment(
                            listOf(
                                TrackPoint(GeoPoint(49.9000, 24.0300), 200.0),
                                TrackPoint(GeoPoint(49.9010, 24.0300), 210.0),
                            ),
                        ),
                    ),
            )

        val profile = profiler.profile(track)
        val range = segments.range(profile, 0.0, profile.distanceMeters)

        // The 90 m jump between the segments is not an elevation step.
        assertEquals(20.0, range?.ascentMeters ?: 0.0, 1e-6)
        assertEquals(0.0, range?.descentMeters ?: 0.0, 1e-6)
    }

    @Test
    fun anUntimedProfileHasNoRangeDuration() {
        val track = syntheticTrack(timed = false)

        val range = segments.range(profiler.profile(track), 0.0, 3 * STEP_METERS)

        assertEquals(60.0, range?.ascentMeters ?: 0.0, 1e-6)
        assertNull(range?.durationMillis)
    }

    @Test
    fun anEmptyShortOrFlatProfileHasNothingToSelect() {
        assertTrue(segments.climbs(ElevationProfile()).isEmpty())
        assertNull(segments.range(ElevationProfile(), 0.0, 100.0))

        val short = profiler.profile(trackOf(300.0))
        assertTrue(segments.climbs(short).isEmpty())
        assertNull(segments.range(short, 0.0, 100.0))

        val flat =
            profiler.profile(
                trackOf(300.0, 300.0, 300.0),
            )
        assertTrue(segments.climbs(flat).isEmpty())
        val range = segments.range(flat, 0.0, flat.distanceMeters)
        assertEquals(0.0, range?.ascentMeters ?: -1.0, 0.0)
        assertEquals(0.0, range?.descentMeters ?: -1.0, 0.0)
        assertEquals(0.0, range?.averageGradePercent ?: -1.0, 0.0)
    }

    @Test
    fun aClimbBelowTheGainOrGradeThresholdIsNotListed() {
        val smallGain = profiler.profile(trackOf(0.0, 5.0, 10.0, 15.0, 20.0))
        val gentle =
            profiler.profile(
                trackOf(*DoubleArray(12) { index -> index * 3.0 }),
            )

        assertTrue(segments.climbs(smallGain).isEmpty())
        assertTrue(segments.climbs(gentle).isEmpty())
    }

    @Test
    fun oneDescendingStepEndsAClimb() {
        val profile = profiler.profile(trackOf(100.0, 120.0, 140.0, 160.0, 159.0, 180.0, 200.0))

        val climbs = segments.climbs(profile)

        assertEquals(2, climbs.size)
        assertEquals(60.0, climbs[0].ascentMeters, 1e-6)
        assertEquals(41.0, climbs[1].ascentMeters, 1e-6)
    }

    @Test
    fun aSustainedDescentIsADescentSegment() {
        val profile = profiler.profile(trackOf(300.0, 280.0, 260.0, 240.0))

        val descents = segments.descents(profile)

        assertEquals(1, descents.size)
        assertEquals(0.0, descents[0].startMeters, 1e-6)
        assertEquals(3 * STEP_METERS, descents[0].endMeters, 1e-6)
        assertEquals(60.0, descents[0].descentMeters, 1e-6)
        assertEquals(17.986407274537644, descents[0].averageGradePercent, 1e-6)
        assertTrue(segments.climbs(profile).isEmpty())
    }

    private fun syntheticTrack(timed: Boolean = true): Track {
        val elevations = listOf(100.0, 120.0, 140.0, 160.0, 155.0, 150.0, 160.0, 180.0, 200.0, 195.0)
        val points =
            elevations.mapIndexed { index, elevation ->
                TrackPoint(
                    position = GeoPoint(LATITUDE_START + index * LATITUDE_STEP, LONGITUDE),
                    elevationMeters = elevation,
                    timeMillis = if (timed) START_MILLIS + index * MILLIS_PER_STEP else null,
                )
            }
        return Track(segments = listOf(TrackSegment(points)))
    }

    /** One track on the same meridian, one step per point, with the given heights. */
    private fun trackOf(vararg elevations: Double): Track =
        Track(
            segments =
                listOf(
                    TrackSegment(
                        elevations.mapIndexed { index, elevation ->
                            TrackPoint(GeoPoint(LATITUDE_START + index * LATITUDE_STEP, LONGITUDE), elevation)
                        },
                    ),
                ),
        )

    private companion object {
        const val LATITUDE_START = 49.8400
        const val LATITUDE_STEP = 0.001
        const val LONGITUDE = 24.0300
        const val START_MILLIS = 1_700_000_000_000L
        const val MILLIS_PER_STEP = 60_000L

        /** The haversine step between two points one [LATITUDE_STEP] apart. */
        const val STEP_METERS = 111.19508023324306
    }
}
