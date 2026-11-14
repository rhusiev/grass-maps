package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.MapTrack
import nl.r1a.grassmap.plugin.api.model.ScreenOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The map tap's track hit test (T-0415). */
class TrackTapTest {
    @Test
    fun aTapOnADrawnLineSelectsThatTrack() {
        val hit =
            TrackTap.hit(
                tracks = listOf(HORIZONTAL, VERTICAL),
                camera = CAMERA,
                viewportWidthDp = WIDTH_DP,
                viewportHeightDp = HEIGHT_DP,
                tap = ScreenOffset(WIDTH_DP / 2f, HEIGHT_DP / 2f),
            )

        assertEquals(HORIZONTAL.id, hit)
    }

    @Test
    fun aTapBetweenTwoLinesSelectsTheNearerOne() {
        val hit =
            TrackTap.hit(
                tracks = listOf(NEAR, FAR),
                camera = CAMERA,
                viewportWidthDp = WIDTH_DP,
                viewportHeightDp = HEIGHT_DP,
                tap = ScreenOffset(WIDTH_DP / 2f, HEIGHT_DP / 2f),
            )

        assertEquals(NEAR.id, hit)
    }

    @Test
    fun aLineCrossingTheViewWithBothEndsOutsideIsStillSelectable() {
        val crossing =
            MapTrack(
                id = "crossing",
                segments = listOf(listOf(GeoPoint(LAT, LON - 0.05), GeoPoint(LAT, LON + 0.05))),
            )

        val hit =
            TrackTap.hit(
                tracks = listOf(crossing),
                camera = CAMERA,
                viewportWidthDp = WIDTH_DP,
                viewportHeightDp = HEIGHT_DP,
                tap = ScreenOffset(WIDTH_DP / 2f, HEIGHT_DP / 2f),
            )

        assertEquals("crossing", hit)
    }

    @Test
    fun aTapClearOfEveryLineSelectsNothing() {
        assertNull(
            TrackTap.hit(
                tracks = listOf(VERTICAL),
                camera = CAMERA,
                viewportWidthDp = WIDTH_DP,
                viewportHeightDp = HEIGHT_DP,
                tap = ScreenOffset(20f, 20f),
            ),
        )
    }

    @Test
    fun aTrackWithoutADrawableSegmentIsNotATarget() {
        val single = MapTrack(id = "single", segments = listOf(listOf(GeoPoint(LAT, LON))))

        assertNull(
            TrackTap.hit(
                tracks = listOf(single),
                camera = CAMERA,
                viewportWidthDp = WIDTH_DP,
                viewportHeightDp = HEIGHT_DP,
                tap = ScreenOffset(WIDTH_DP / 2f, HEIGHT_DP / 2f),
            ),
        )
    }

    @Test
    fun theToleranceIsTheDesignsFingerReach() {
        assertEquals(14f, TrackTap.TAP_TOLERANCE_DP)
    }

    private companion object {
        const val WIDTH_DP = 360f
        const val HEIGHT_DP = 720f
        const val LAT = 49.95
        const val LON = 23.95
        val CAMERA = Camera(center = GeoPoint(LAT, LON), zoom = 14.0)

        /** A horizontal line through the camera centre. */
        val HORIZONTAL =
            MapTrack(
                id = "horizontal",
                segments =
                    listOf(
                        listOf(
                            GeoPoint(LAT, LON - 0.002),
                            GeoPoint(LAT, LON + 0.002),
                        ),
                    ),
            )

        /** A line far enough east that its nearest point is outside the tolerance. */
        val VERTICAL =
            MapTrack(
                id = "vertical",
                segments =
                    listOf(
                        listOf(
                            GeoPoint(LAT + 0.01, LON + 0.02),
                            GeoPoint(LAT - 0.01, LON + 0.02),
                        ),
                    ),
            )

        val NEAR =
            MapTrack(
                id = "near",
                segments = listOf(listOf(GeoPoint(LAT + 0.0001, LON - 0.001), GeoPoint(LAT + 0.0001, LON + 0.001))),
            )
        val FAR =
            MapTrack(
                id = "far",
                segments = listOf(listOf(GeoPoint(LAT - 0.0015, LON - 0.001), GeoPoint(LAT - 0.0015, LON + 0.001))),
            )
    }
}
