package nl.r1a.grassmap.plugins.direct

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.ceil

class GeodesicTest {
    @Test
    fun oneDegreeOfLatitudeIsThePublishedLength() {
        val metres = Geodesic.distanceMetres(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0))

        // A degree of latitude is a fixed fraction of the circumference, so the
        // expected value does not come from the same code under test.
        assertEquals(CIRCUMFERENCE_METRES / DEGREES_PER_TURN, metres, LENGTH_TOLERANCE_METRES)
    }

    @Test
    fun aLongPairMatchesAnIndependentlyComputedHaversine() {
        val engine = Geodesic.distanceMetres(LVIV, WARSAW)
        val independent = haversineMetres(LVIV, WARSAW)

        assertEquals(independent, engine, LENGTH_TOLERANCE_METRES)
        assertTrue(engine > 300_000.0)
        assertTrue(engine < 400_000.0)
    }

    @Test
    fun theSampledLineHasTheSameLengthAsTheGeodesic() {
        val total = Geodesic.distanceMetres(LVIV, WARSAW)
        val segments = ceil(total / MAX_VERTEX_SPACING_METRES).toInt()

        val points = Geodesic.polyline(LVIV, WARSAW, segments)
        val sampled = points.zipWithNext { from, to -> Geodesic.distanceMetres(from, to) }.sum()

        assertEquals(segments + 1, points.size)
        assertEquals(total, sampled, SAMPLED_TOLERANCE_METRES)
    }

    @Test
    fun everyInteriorPointLiesOnTheArc() {
        val points = Geodesic.polyline(LVIV, WARSAW, 40)
        val total = Geodesic.distanceMetres(LVIV, WARSAW)

        for (point in points.drop(1).dropLast(1)) {
            val before = Geodesic.distanceMetres(LVIV, point)
            val after = Geodesic.distanceMetres(point, WARSAW)
            assertEquals(total, before + after, SAMPLED_TOLERANCE_METRES)
            assertTrue(before > 0.0)
            assertTrue(after > 0.0)
        }
    }

    @Test
    fun anEastWestLegBowsPolewardInsteadOfCuttingTheChord() {
        val points = Geodesic.polyline(GeoPoint(49.0, 24.0), GeoPoint(49.0, 34.0), 2)

        // The linear midpoint of the chord keeps latitude 49; the great circle
        // between two points at 49 degrees reaches about 49.26 degrees north.
        assertTrue(points[1].latitude > 49.1)
        assertEquals(49.0, points.first().latitude, 1e-9)
        assertEquals(49.0, points.last().latitude, 1e-9)
    }

    @Test
    fun aMeridianLegKeepsItsLongitude() {
        val points = Geodesic.polyline(GeoPoint(49.0, 24.0), GeoPoint(50.0, 24.0), 10)

        assertEquals(11, points.size)
        for (point in points) {
            assertEquals(24.0, point.longitude, 1e-9)
        }
    }

    @Test
    fun theTwoEndsAreTheInputPointsExactly() {
        val points = Geodesic.polyline(LVIV, WARSAW, 7)

        assertEquals(LVIV, points.first())
        assertEquals(WARSAW, points.last())
    }

    private companion object {
        /** The highest error a sampled line may show against its own geodesic, 1 mm. */
        const val SAMPLED_TOLERANCE_METRES = 1e-3

        const val LENGTH_TOLERANCE_METRES = 1e-6
        const val DEGREES_PER_TURN = 360.0
        const val MAX_VERTEX_SPACING_METRES = 1_000.0

        val CIRCUMFERENCE_METRES: Double = 2 * PI * EARTH_RADIUS_METRES
    }
}
