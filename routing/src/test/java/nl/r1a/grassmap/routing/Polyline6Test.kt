package nl.r1a.grassmap.routing

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class Polyline6Test {
    @Test
    fun aLineRoundTripsAtSixDecimalPlaces() {
        val points =
            listOf(
                GeoPoint(49.8419, 24.0316),
                GeoPoint(49.8389, 24.0289),
                GeoPoint(49.8354, 24.0231),
            )

        val decoded = Polyline6.decode(Polyline6.encode(points))

        assertEquals(points.size, decoded.size)
        points.zip(decoded).forEach { (expected, actual) ->
            assertEquals(expected.latitude, actual.latitude, 0.000001)
            assertEquals(expected.longitude, actual.longitude, 0.000001)
        }
    }

    @Test
    fun negativeDeltasRoundTrip() {
        val points =
            listOf(
                GeoPoint(49.9, 23.9),
                GeoPoint(49.8, 24.1),
                GeoPoint(49.95, 23.85),
            )

        val decoded = Polyline6.decode(Polyline6.encode(points))

        assertEquals(points.size, decoded.size)
        points.zip(decoded).forEach { (expected, actual) ->
            assertEquals(expected.latitude, actual.latitude, 0.000001)
            assertEquals(expected.longitude, actual.longitude, 0.000001)
        }
    }

    @Test
    fun anEmptyLineEncodesToAnEmptyString() {
        assertEquals("", Polyline6.encode(emptyList()))
        assertEquals(emptyList<GeoPoint>(), Polyline6.decode(""))
    }
}
