package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.ScreenOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The marker overlay's projection (T-0203).
 *
 * The camera centre draws at the viewport centre, plus the same position offset
 * the map host was given. A north-up camera puts an eastern point to the right;
 * a camera facing east puts it at the top. These are the two mistakes a naive
 * overlay makes, so both are pinned.
 */
class SearchMarkerProjectionTest {
    @Test
    fun theCameraCentreDrawsAtTheViewportCentre() {
        val offset =
            SearchMarkerProjection.offset(
                position = CENTRE,
                camera = camera(),
                viewportWidthDp = WIDTH_DP,
                viewportHeightDp = HEIGHT_DP,
            )

        assertEquals(WIDTH_DP / 2f, offset.xDp, TOLERANCE_DP)
        assertEquals(HEIGHT_DP / 2f, offset.yDp, TOLERANCE_DP)
        assertTrue(offset.visible)
    }

    @Test
    fun aNorthUpCameraPutsAnEasternPointOnTheRight() {
        val offset =
            SearchMarkerProjection.offset(
                position = EAST,
                camera = camera(bearing = 0.0),
                viewportWidthDp = WIDTH_DP,
                viewportHeightDp = HEIGHT_DP,
            )

        assertTrue("east must be right of centre", offset.xDp > WIDTH_DP / 2f)
        assertEquals(HEIGHT_DP / 2f, offset.yDp, TOLERANCE_DP)
    }

    @Test
    fun aCameraFacingEastPutsTheSamePointAtTheTop() {
        val offset =
            SearchMarkerProjection.offset(
                position = EAST,
                camera = camera(bearing = 90.0),
                viewportWidthDp = WIDTH_DP,
                viewportHeightDp = HEIGHT_DP,
            )

        assertEquals(WIDTH_DP / 2f, offset.xDp, TOLERANCE_DP)
        assertTrue("east must move up when the map faces east", offset.yDp < HEIGHT_DP / 2f)
    }

    @Test
    fun thePositionOffsetMovesTheAnchorWithThePuck() {
        val anchor = ScreenOffset(x = 10f, y = -40f)
        val offset =
            SearchMarkerProjection.offset(
                position = CENTRE,
                camera = camera(),
                viewportWidthDp = WIDTH_DP,
                viewportHeightDp = HEIGHT_DP,
                anchor = anchor,
            )

        assertEquals(WIDTH_DP / 2f + anchor.x, offset.xDp, TOLERANCE_DP)
        assertEquals(HEIGHT_DP / 2f + anchor.y, offset.yDp, TOLERANCE_DP)
    }

    @Test
    fun aPointAcrossTheCityIsOutsideTheView() {
        val offset =
            SearchMarkerProjection.offset(
                position = GeoPoint(50.5, 25.5),
                camera = camera(zoom = 14.0),
                viewportWidthDp = WIDTH_DP,
                viewportHeightDp = HEIGHT_DP,
            )

        assertFalse(offset.visible)
    }

    @Test
    fun aZeroSizedViewportDrawsNothing() {
        val offset =
            SearchMarkerProjection.offset(
                position = CENTRE,
                camera = camera(),
                viewportWidthDp = 0f,
                viewportHeightDp = 0f,
            )

        assertFalse(offset.visible)
    }

    private fun camera(
        bearing: Double = 0.0,
        zoom: Double = 15.0,
    ): Camera = Camera(center = CENTRE, zoom = zoom, bearing = bearing)

    private companion object {
        val CENTRE = GeoPoint(49.8419, 24.0316)

        /** About 660 m east of the centre at this latitude. */
        val EAST = GeoPoint(49.8419, 24.0416)

        const val WIDTH_DP = 400f
        const val HEIGHT_DP = 800f
        const val TOLERANCE_DP = 0.01f
    }
}
