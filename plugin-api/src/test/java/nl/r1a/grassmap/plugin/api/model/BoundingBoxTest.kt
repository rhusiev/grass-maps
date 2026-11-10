package nl.r1a.grassmap.plugin.api.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The viewport filter's inclusive edges (T-0206). */
class BoundingBoxTest {
    @Test
    fun aPointOnEveryEdgeIsInside() {
        for (point in listOf(
            SOUTH_WEST,
            NORTH_EAST,
            GeoPoint(SOUTH_WEST.latitude, NORTH_EAST.longitude),
            GeoPoint(NORTH_EAST.latitude, SOUTH_WEST.longitude),
        )) {
            assertTrue("$point on the edge of $BOX", BOX.contains(point))
        }
    }

    @Test
    fun aPointOutsideOrBeyondAnEdgeIsNotInside() {
        assertFalse(BOX.contains(GeoPoint(SOUTH_WEST.latitude - DELTA, SOUTH_WEST.longitude)))
        assertFalse(BOX.contains(GeoPoint(NORTH_EAST.latitude + DELTA, NORTH_EAST.longitude)))
        assertFalse(BOX.contains(GeoPoint(SOUTH_WEST.latitude, SOUTH_WEST.longitude - DELTA)))
        assertFalse(BOX.contains(GeoPoint(NORTH_EAST.latitude, NORTH_EAST.longitude + DELTA)))
    }

    @Test
    fun aDegenerateBoxContainsOnlyItsOwnPoint() {
        val point = GeoPoint(49.8419, 24.0316)
        val box = BoundingBox(point, point)

        assertTrue(box.contains(point))
        assertFalse(box.contains(GeoPoint(point.latitude + DELTA, point.longitude)))
    }

    private companion object {
        const val DELTA = 1e-9
        val SOUTH_WEST = GeoPoint(49.80, 24.00)
        val NORTH_EAST = GeoPoint(49.90, 24.10)
        val BOX = BoundingBox(SOUTH_WEST, NORTH_EAST)
    }
}
