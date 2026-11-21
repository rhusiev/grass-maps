package nl.r1a.grassmaps.tracks

import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.Track
import nl.r1a.grassmaps.plugin.api.model.TrackPoint
import nl.r1a.grassmaps.plugin.api.model.TrackSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The elevation profile the chart plots (T-0404). */
class ElevationProfilerTest {
    private val profiler = ElevationProfiler()

    @Test
    fun theProfileSamplesEveryHeightsPointAtTheEngineDistance() {
        val track =
            track(
                point(latitude = 49.8400, elevation = 300.0),
                point(latitude = 49.8410, elevation = 305.0),
                point(latitude = 49.8420, elevation = 301.5),
            )

        val profile = profiler.profile(track)

        assertEquals(3, profile.samples.size)
        assertEquals(0.0, profile.samples[0].distanceMeters, 1e-9)
        assertEquals(300.0, profile.samples[0].elevationMeters, 0.0)
        // The same haversine step the statistics use: one line, one constant.
        val analysis = TrackAnalyzer().analyze(track)
        assertEquals(analysis.distanceMeters, profile.samples.last().distanceMeters, 1e-9)
        assertEquals(305.0, profile.samples[1].elevationMeters, 0.0)
        assertEquals(301.5, profile.samples[2].elevationMeters, 0.0)
        assertEquals(300.0, profile.minElevationMeters ?: 0.0, 0.0)
        assertEquals(305.0, profile.maxElevationMeters ?: 0.0, 0.0)
    }

    @Test
    fun aPointWithoutHeightAddsNoSampleButKeepsTheDistance() {
        val track =
            track(
                TrackSegment(
                    listOf(
                        point(latitude = 49.8400, elevation = 300.0),
                        TrackPoint(GeoPoint(49.8410, 24.0300)),
                        point(latitude = 49.8420, elevation = 310.0),
                    ),
                ),
            )

        val profile = profiler.profile(track)

        assertEquals(2, profile.samples.size)
        assertEquals(
            TrackAnalyzer().analyze(track).distanceMeters,
            profile.samples[1].distanceMeters,
            1e-9,
        )
    }

    @Test
    fun aSegmentBoundaryAddsNoDistanceButKeepsBothSamples() {
        val track =
            Track(
                segments =
                    listOf(
                        TrackSegment(listOf(point(latitude = 49.8400, elevation = 300.0))),
                        TrackSegment(listOf(point(latitude = 49.9000, elevation = 320.0))),
                    ),
            )

        val profile = profiler.profile(track)

        assertEquals(2, profile.samples.size)
        assertEquals(0.0, profile.samples[1].distanceMeters, 1e-9)
        assertEquals(0.0, profile.distanceMeters, 1e-9)
    }

    @Test
    fun aTimedTrackCarriesElapsedAndTimeOfDayEndingAtTheEngineDuration() {
        val start = 1_700_000_000_000L
        val track =
            track(
                point(latitude = 49.8400, elevation = 300.0, timeMillis = start),
                point(latitude = 49.8410, elevation = 305.0, timeMillis = start + 120_000L),
                point(latitude = 49.8420, elevation = 301.0, timeMillis = start + 300_000L),
            )

        val profile = profiler.profile(track)

        assertEquals(0L, profile.samples[0].elapsedMillis)
        assertEquals(120_000L, profile.samples[1].elapsedMillis)
        assertEquals(300_000L, profile.samples[2].elapsedMillis)
        assertEquals(start, profile.samples[0].timeOfDayMillis)
        assertEquals(start + 300_000L, profile.samples[2].timeOfDayMillis)
        assertEquals(300_000L, profile.durationMillis)
        assertTrue(profile.hasElapsedTime)
        assertTrue(profile.hasTimeOfDay)
    }

    @Test
    fun anUntimedTrackHasNoTimeAxes() {
        val profile = profiler.profile(track(point(elevation = 300.0), point(elevation = 320.0)))

        assertNull(profile.durationMillis)
        assertNull(profile.samples[1].elapsedMillis)
        assertNull(profile.samples[1].timeOfDayMillis)
        assertFalse(profile.hasElapsedTime)
        assertFalse(profile.hasTimeOfDay)
    }

    @Test
    fun aSingleHeightsPointIsNotDrawable() {
        val profile = profiler.profile(track(point(elevation = 300.0)))

        assertFalse(profile.isDrawable)
        assertEquals(1, profile.samples.size)
    }

    @Test
    fun aTrackWithoutHeightsGivesNoProfile() {
        val profile = profiler.profile(track(TrackPoint(GeoPoint(49.84, 24.03))))

        assertTrue(profile.samples.isEmpty())
        assertFalse(profile.isDrawable)
    }

    @Test
    fun aRouteSpreadsItsOwnDurationOverTheDistance() {
        val start = 1_700_000_000_000L
        val duration = 1_140_000L
        val positions =
            listOf(
                GeoPoint(49.8400, 24.0300),
                GeoPoint(49.8410, 24.0300),
                GeoPoint(49.8420, 24.0300),
            )

        val profile =
            profiler.lineProfile(
                positions = positions,
                elevations = listOf(280.0, 300.0, 320.0),
                durationMillis = duration,
                departureEpochMillis = start,
            )

        assertEquals(3, profile.samples.size)
        assertEquals(0L, profile.samples[0].elapsedMillis)
        assertEquals(duration, profile.samples.last().elapsedMillis)
        assertEquals(start + duration, profile.samples.last().timeOfDayMillis)
        assertEquals(320.0, profile.samples.last().elevationMeters, 0.0)
        assertTrue(profile.hasElapsedTime)
        assertTrue(profile.hasTimeOfDay)
    }

    @Test
    fun aRouteSampleWithNoMeasuredHeightIsSkipped() {
        val positions = listOf(GeoPoint(49.84, 24.03), GeoPoint(49.841, 24.03), GeoPoint(49.842, 24.03))

        val profile =
            profiler.lineProfile(
                positions = positions,
                elevations = listOf(null, 300.0, null),
                durationMillis = 60_000L,
                departureEpochMillis = 0L,
            )

        assertFalse(profile.isDrawable)
        assertTrue(profile.samples.isEmpty())
    }

    /** A relation's way chain must not add the jump across a gap between members. */
    @Test
    fun aRelationWayChainKeepsItsGapsOutOfTheDistance() {
        val first = listOf(GeoPoint(49.8400, 24.0300), GeoPoint(49.8410, 24.0300))
        val second = listOf(GeoPoint(49.8500, 24.0300), GeoPoint(49.8490, 24.0300))

        val profile =
            profiler.linesProfile(
                lines = listOf(first, second),
                elevations = listOf(listOf(300.0, 320.0), listOf(340.0, 360.0)),
                durationMillis = 0L,
                departureEpochMillis = 0L,
            )

        val firstLength = profiler.lineProfile(first, listOf(300.0, 320.0), 0L, 0L).distanceMeters
        val secondLength = profiler.lineProfile(second, listOf(340.0, 360.0), 0L, 0L).distanceMeters
        assertEquals(4, profile.samples.size)
        assertEquals(firstLength, profile.samples[1].distanceMeters, 1e-9)
        assertEquals(firstLength, profile.samples[2].distanceMeters, 1e-9)
        assertEquals(firstLength + secondLength, profile.samples.last().distanceMeters, 1e-9)
        assertEquals(firstLength + secondLength, profile.distanceMeters, 1e-9)
        assertNull(profile.durationMillis)
    }

    /**
     * A noisy track's chart must end where the analysis ends. The raw sum over the
     * same fixes is 40 % longer than the walk, so a profile that accumulated raw
     * steps would stretch the axis beside the recorded distance.
     */
    @Test
    fun aNoisyTracksAxisEndsAtTheAnalysedDistance() {
        val segment = noisyWalk(points = 200)
        val track = track(segment)

        val profile = ElevationProfiler().profile(track)
        val analysis = TrackAnalyzer().analyze(track)
        val raw =
            (1 until segment.points.size).sumOf { index ->
                distanceMeters(segment.points[index - 1].position, segment.points[index].position)
            }

        assertEquals(analysis.distanceMeters, profile.samples.last().distanceMeters, 1e-9)
        assertTrue("the wander must not reach the axis, was ${profile.distanceMeters}", profile.distanceMeters < raw * 0.8)
        for (index in 1 until profile.samples.size) {
            assertTrue(
                "the axis went backwards at $index",
                profile.samples[index].distanceMeters >= profile.samples[index - 1].distanceMeters,
            )
        }
    }

    private fun noisyWalk(points: Int): TrackSegment {
        var state = 20_260_111

        fun wander(amplitude: Double): Double {
            state = state * 1_103_515_245 + 12_345
            return (((state ushr 16) and 0x7FFF) / 16_384.0 - 1.0) * amplitude
        }
        val trackPoints =
            (0 until points).map { index ->
                val north = index * 2.7 + if (index == 0) 0.0 else wander(4.0)
                val east = if (index == 0) 0.0 else wander(4.0)
                TrackPoint(
                    position =
                        GeoPoint(
                            latitude = 49.84 + north / 111_195.08,
                            longitude = 24.03 + east / 71_700.0,
                        ),
                    elevationMeters = 300.0 + index * 0.1,
                    timeMillis = index * 2_000L,
                )
            }
        return TrackSegment(trackPoints)
    }

    private fun track(vararg points: TrackPoint): Track = track(TrackSegment(points.toList()))

    private fun track(segment: TrackSegment): Track = Track(segments = listOf(segment))

    private fun point(
        latitude: Double = 49.8400,
        elevation: Double? = null,
        timeMillis: Long? = null,
    ): TrackPoint = TrackPoint(GeoPoint(latitude, 24.0300), elevation, timeMillis)
}
