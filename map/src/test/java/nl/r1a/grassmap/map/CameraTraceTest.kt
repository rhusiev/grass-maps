package nl.r1a.grassmap.map

import nl.r1a.grassmap.plugin.api.model.CameraUpdate
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.spatialk.geojson.Position

/**
 * The camera stream is the observable result of every gesture (T-0104). These
 * tests read the same seam a gesture trace ends in: the engine position mapped
 * to the domain camera, and a camera update applied to the current position.
 */
class CameraTraceTest {
    @Test
    fun aPanTraceChangesTheCentreAndKeepsZoomAndBearing() {
        val start = position()
        val panned = start.apply(CameraUpdate(center = GeoPoint(49.9, 24.1)))

        val camera = cameraModel(panned)

        assertEquals(49.9, camera.center.latitude, 0.0)
        assertEquals(24.1, camera.center.longitude, 0.0)
        assertEquals(start.zoom, camera.zoom, 0.0)
        assertEquals(start.bearing, camera.bearing, 0.0)
    }

    @Test
    fun aDoubleTapTraceAddsExactlyOneZoomLevel() {
        val start = position()

        val camera = cameraModel(start.apply(CameraUpdate(zoom = start.zoom + 1.0)))

        assertEquals(start.zoom + 1.0, camera.zoom, 0.0)
        assertEquals(start.bearing, camera.bearing, 0.0)
        assertEquals(start.center.latitude, camera.center.latitude, 0.0)
    }

    @Test
    fun aRotateTraceChangesBearingByTheTracedAngle() {
        val start = position()

        val camera = cameraModel(start.apply(CameraUpdate(bearing = start.bearing + 30.0)))

        assertEquals(30.0, camera.bearing, 0.0)
        assertEquals(start.zoom, camera.zoom, 0.0)
    }

    @Test
    fun anUpdateWithoutAFieldKeepsThatField() {
        val start = position()

        val camera = cameraModel(start.apply(CameraUpdate()))

        assertEquals(cameraModel(start), camera)
    }

    private fun position(): CameraPosition =
        CameraPosition(
            center = Position(longitude = 24.0316, latitude = 49.8419),
            zoom = 12.5,
            bearing = 0.0,
            pitch = 0.0,
        )
}
