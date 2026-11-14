package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.MapTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The map tap's track hit test (T-0415). */
class TrackTapTest {
    @Test
    fun aTapOnADrawnLineSelectsThatTrack() {
        val hit = TrackTap.hit(listOf(HORIZONTAL, VERTICAL), CENTRE, METRES_PER_DP)

        assertEquals(HORIZONTAL.id, hit)
    }

    @Test
    fun aTapBetweenTwoLinesSelectsTheNearerOne() {
        val hit = TrackTap.hit(listOf(FAR, NEAR), CENTRE, METRES_PER_DP)

        assertEquals(NEAR.id, hit)
    }

    @Test
    fun aTapClearOfEveryLineSelectsNothing() {
        assertNull(TrackTap.hit(listOf(VERTICAL), CENTRE, METRES_PER_DP))
    }

    @Test
    fun theToleranceFollowsTheMapsScale() {
        // NEAR is about 2 m north of the tap; a 1 m tolerance must not reach it.
        assertNull(TrackTap.hit(listOf(NEAR), CENTRE, METRES_PER_DP / TOLERANCE_DIVISOR))
    }

    @Test
    fun aTrackWithoutADrawableSegmentIsNotATarget() {
        val single = MapTrack(id = "single", segments = listOf(listOf(CENTRE)))

        assertNull(TrackTap.hit(listOf(single), CENTRE, METRES_PER_DP))
    }

    @Test
    fun aMissingScaleSelectsNothing() {
        assertNull(TrackTap.hit(listOf(HORIZONTAL), CENTRE, 0.0))
    }

    @Test
    fun theToleranceIsTheDesignsFingerReach() {
        assertEquals(14f, TrackTap.TAP_TOLERANCE_DP)
    }

    private companion object {
        const val LAT = 49.95
        const val LON = 23.95
        const val METRES_PER_DP = 0.5
        const val TOLERANCE_DIVISOR = 20.0
        val CENTRE = GeoPoint(LAT, LON)

        /** A horizontal line through the tap. */
        val HORIZONTAL =
            MapTrack(
                id = "horizontal",
                segments = listOf(listOf(GeoPoint(LAT, LON - 0.002), GeoPoint(LAT, LON + 0.002))),
            )

        /** A line far enough east that its nearest point is outside the tolerance. */
        val VERTICAL =
            MapTrack(
                id = "vertical",
                segments = listOf(listOf(GeoPoint(LAT + 0.01, LON + 0.02), GeoPoint(LAT - 0.01, LON + 0.02))),
            )

        /** About 2 m north of the tap. */
        val NEAR =
            MapTrack(
                id = "near",
                segments = listOf(listOf(GeoPoint(LAT + 0.00002, LON - 0.001), GeoPoint(LAT + 0.00002, LON + 0.001))),
            )

        /** About 22 m south of the tap. */
        val FAR =
            MapTrack(
                id = "far",
                segments = listOf(listOf(GeoPoint(LAT - 0.0002, LON - 0.001), GeoPoint(LAT - 0.0002, LON + 0.001))),
            )
    }
}
