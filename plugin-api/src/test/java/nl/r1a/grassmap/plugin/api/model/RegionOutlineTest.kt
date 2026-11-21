package nl.r1a.grassmaps.plugin.api.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionOutlineTest {
    private val square =
        listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 2.0), GeoPoint(2.0, 2.0), GeoPoint(2.0, 0.0), GeoPoint(0.0, 0.0))
    private val far =
        listOf(GeoPoint(10.0, 10.0), GeoPoint(10.0, 12.0), GeoPoint(12.0, 12.0), GeoPoint(12.0, 10.0), GeoPoint(10.0, 10.0))

    @Test
    fun containsIsTrueInsideAnyRingAndFalseOutsideAll() {
        val outline = RegionOutline("a", listOf(square, far))

        assertTrue(outline.contains(GeoPoint(1.0, 1.0)))
        assertTrue(outline.contains(GeoPoint(11.0, 11.0)))
        assertFalse(outline.contains(GeoPoint(5.0, 5.0)))
        assertFalse(outline.contains(GeoPoint(3.0, 1.0)))
    }

    @Test
    fun boundsCoverEveryRing() {
        val bounds = RegionOutline("a", listOf(square, far)).bounds

        assertEquals(GeoPoint(0.0, 0.0), bounds?.southWest)
        assertEquals(GeoPoint(12.0, 12.0), bounds?.northEast)
    }

    @Test
    fun anOutlineWithoutPointsHasNoBoundsAndContainsNothing() {
        val outline = RegionOutline("a")

        assertNull(outline.bounds)
        assertFalse(outline.contains(GeoPoint(0.0, 0.0)))
    }
}
