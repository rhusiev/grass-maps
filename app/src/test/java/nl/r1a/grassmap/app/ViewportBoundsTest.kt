package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.plugin.api.model.Camera
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The download panel plans from the box of the camera at the moment it reads it. */
class ViewportBoundsTest {
    @Test
    fun theBoxFollowsTheCameraItIsGiven() {
        val before = viewportBounds(Camera(LVIV, zoom = ZOOM), WIDTH_DP, HEIGHT_DP)
        val moved = viewportBounds(Camera(KYIV, zoom = ZOOM), WIDTH_DP, HEIGHT_DP)

        assertTrue(moved.southWest.latitude > before.southWest.latitude)
        assertEquals(KYIV.latitude, (moved.southWest.latitude + moved.northEast.latitude) / 2, TOLERANCE)
        assertEquals(LVIV.longitude, (before.southWest.longitude + before.northEast.longitude) / 2, TOLERANCE)
    }

    @Test
    fun aHigherZoomGivesASmallerBox() {
        val wide = viewportBounds(Camera(LVIV, zoom = ZOOM), WIDTH_DP, HEIGHT_DP)
        val close = viewportBounds(Camera(LVIV, zoom = ZOOM + 2), WIDTH_DP, HEIGHT_DP)

        assertTrue(close.northEast.latitude - close.southWest.latitude < wide.northEast.latitude - wide.southWest.latitude)
    }

    private companion object {
        const val ZOOM = 10.0
        const val WIDTH_DP = 400
        const val HEIGHT_DP = 800
        const val TOLERANCE = 1e-9
        val LVIV = GeoPoint(49.84, 24.03)
        val KYIV = GeoPoint(50.45, 30.52)
    }
}
