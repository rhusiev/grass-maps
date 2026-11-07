package nl.r1a.grassmap.navigation

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineLocatorTest {
    private val line =
        listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.0, 0.001),
            GeoPoint(0.001, 0.001),
        )

    @Test
    fun aPointOnTheLineHasNoDistance() {
        val projection = requireNotNull(PolylineLocator(line).locate(GeoPoint(0.0, 0.0005)))

        assertTrue(projection.distanceMeters < 0.01)
        assertTrue(projection.alongMeters > 50.0)
        assertTrue(projection.remainingMeters > 50.0)
    }

    @Test
    fun aPointBesideTheLineReportsThePerpendicularDistance() {
        val projection = requireNotNull(PolylineLocator(line).locate(GeoPoint(0.0001, 0.0005)))

        assertEquals(11.1, projection.distanceMeters, 0.5)
        assertEquals(line.first().latitude, projection.position.latitude, 0.000_001)
    }

    @Test
    fun aPointBeyondTheEndClampsToTheLastVertex() {
        val locator = PolylineLocator(line)
        val projection = requireNotNull(locator.locate(GeoPoint(0.002, 0.002)))

        assertEquals(locator.totalMeters(), projection.alongMeters, 0.01)
        assertEquals(0.0, projection.remainingMeters, 0.01)
        assertEquals(line.last().latitude, projection.position.latitude, 0.000_001)
    }

    @Test
    fun theWholeLineIsMeasuredAndPointAtInterpolates() {
        val locator = PolylineLocator(line)

        assertTrue(locator.totalMeters() > 200.0)
        val middle = requireNotNull(locator.pointAt(locator.totalMeters() / 2.0))
        assertTrue(middle.longitude > 0.0)
    }

    @Test
    fun pointsBetweenKeepsTheVerticesInsideTheRange() {
        val locator = PolylineLocator(line)
        val points = locator.pointsBetween(10.0, 150.0)

        assertTrue(points.size >= 3)
        assertTrue(points.first().longitude < 0.0002)
        assertTrue(points.last().latitude > 0.0)
    }

    @Test
    fun aSinglePointLineHasNoProjection() {
        assertEquals(null, PolylineLocator(listOf(GeoPoint(0.0, 0.0))).locate(GeoPoint(0.0, 0.0)))
    }
}
