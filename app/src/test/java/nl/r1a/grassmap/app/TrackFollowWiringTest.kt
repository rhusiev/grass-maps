package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.Track
import nl.r1a.grassmap.plugin.api.model.TrackFollowStart
import nl.r1a.grassmap.plugin.api.model.TrackPoint
import nl.r1a.grassmap.plugin.api.model.TrackSegment
import nl.r1a.grassmap.tracks.StoredTrack
import nl.r1a.grassmap.tracks.TrackAnalyzer
import nl.r1a.grassmap.tracks.TrackId
import nl.r1a.grassmap.tracks.distanceMeters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The wiring from a stored track to the guidance seam (T-0412): the line is
 * every segment in file order, the pace is the analysis' own time, and a track
 * without a followable line is refused before the sheet opens.
 */
class TrackFollowWiringTest {
    @Test
    fun theLineJoinsEverySegmentInFileOrder() {
        val line = stored(TWO_SEGMENTS).followLine()

        assertEquals(FIRST, line.first())
        assertEquals(SECOND_SEGMENT_LAST, line.last())
        assertEquals(4, line.size)
    }

    @Test
    fun theRoutesPaceIsTheAnalysisMovingTime() {
        val track = stored(TWO_SEGMENTS)

        val route = track.followRoute(TrackFollowStart.Start, FIRST) ?: throw AssertionError("a route")

        assertEquals(MOVING_SECONDS, route.durationSeconds, SECONDS_TOLERANCE)
        assertEquals(lineMeters(track), route.distanceMeters, METRES_TOLERANCE)
    }

    @Test
    fun aTrackWithoutTimingStillBuildsARoute() {
        val track = stored(TWO_SEGMENTS.copy(segments = TWO_SEGMENTS.segments.map { segment -> segment.untimed() }))

        val route = track.followRoute(TrackFollowStart.Start, FIRST) ?: throw AssertionError("a route")

        assertEquals(null, track.analysis.movingTimeMillis)
        assertEquals(null, track.analysis.durationMillis)
        assertNotNull(route)
        assertEquals(lineMeters(track), route.distanceMeters, METRES_TOLERANCE)
    }

    @Test
    fun aTrackWithoutALineHasNoPlanAndNoRoute() {
        val onePoint =
            stored(
                Track(
                    segments = listOf(TrackSegment(points = listOf(trackPoint(FIRST, 0L)))),
                ),
            )

        assertNull(onePoint.followPlan(FIRST))
        assertNull(onePoint.followRoute(TrackFollowStart.Nearest, FIRST))
    }

    private fun stored(track: Track): StoredTrack =
        StoredTrack(
            id = TrackId(TRACK_ID),
            name = TRACK_NAME,
            track = track,
            analysis = TrackAnalyzer().analyze(track),
        )

    private fun lineMeters(track: StoredTrack): Double {
        val line = track.followLine()
        return line.zipWithNext { from, to -> distanceMeters(from, to) }.sum()
    }

    private fun TrackPoint.untimed(): TrackPoint = copy(timeMillis = null)

    private fun TrackSegment.untimed(): TrackSegment = TrackSegment(points = points.map { it.untimed() })

    private companion object {
        fun trackPoint(
            position: GeoPoint,
            timeMillis: Long?,
        ): TrackPoint = TrackPoint(position = position, timeMillis = timeMillis)

        const val TRACK_ID = "0123456789abcdef"
        const val TRACK_NAME = "Znesinnia ridges"
        const val MOVING_SECONDS = 60.0
        const val SECONDS_TOLERANCE = 1.0
        const val METRES_TOLERANCE = 0.01

        val FIRST = GeoPoint(49.8400, 24.0300)
        val SECOND_SECOND_SEGMENT = GeoPoint(49.8416, 24.0300)
        val SECOND_SEGMENT_LAST = GeoPoint(49.8427, 24.0300)

        /** Two legs a pause apart, timed 30 s per step inside the first leg. */
        val TWO_SEGMENTS =
            Track(
                name = TRACK_NAME,
                segments =
                    listOf(
                        TrackSegment(
                            points =
                                listOf(
                                    trackPoint(FIRST, 0L),
                                    trackPoint(GeoPoint(49.8403, 24.0300), 30_000L),
                                ),
                        ),
                        TrackSegment(
                            points =
                                listOf(
                                    trackPoint(SECOND_SECOND_SEGMENT, 600_000L),
                                    trackPoint(SECOND_SEGMENT_LAST, 630_000L),
                                ),
                        ),
                    ),
            )
    }
}
