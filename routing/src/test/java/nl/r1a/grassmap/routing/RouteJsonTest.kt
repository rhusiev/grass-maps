package nl.r1a.grassmap.routing

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.AttributeValue
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteInstruction
import nl.r1a.grassmap.plugin.api.provider.RouteSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteJsonTest {
    @Test
    fun aRouteRoundTrips() {
        val route = route()

        val decoded = RouteJson.decode(RouteJson.encode(route))

        assertEquals(Outcome.Success(route.copy(rawResponse = null)), decoded)
    }

    @Test
    fun attributesKeepTheirTypes() {
        val decoded = RouteJson.decode(RouteJson.encode(route()))

        val attributes =
            (decoded as Outcome.Success<Route>)
                .value.segments
                .single()
                .attributes
        assertEquals(AttributeValue.NumberValue(320.5), attributes["elevation"])
        assertEquals(AttributeValue.TextValue("dirt"), attributes["surface"])
        assertEquals(AttributeValue.BooleanValue(true), attributes["synthetic"])
    }

    @Test
    fun aDocumentWithoutGeometryIsRejected() {
        assertEquals(
            Outcome.Failure(AppError.InvalidInput),
            RouteJson.decode("""{"distanceMeters": 1.0, "durationSeconds": 2.0}"""),
        )
    }

    @Test
    fun theRawResponseIsNotEncoded() {
        val encoded = RouteJson.encode(route())

        assertTrue(!encoded.contains("rawResponse"))
    }

    private fun route(): Route =
        Route(
            geometry =
                listOf(
                    GeoPoint(49.8419, 24.0316),
                    GeoPoint(49.8389, 24.0289),
                    GeoPoint(49.8354, 24.0231),
                ),
            distanceMeters = 1_009.0,
            durationSeconds = 716.27,
            segments =
                listOf(
                    RouteSegment(
                        startIndex = 0,
                        endIndex = 3,
                        distanceMeters = 1_009.0,
                        durationSeconds = 716.27,
                        attributes =
                            mapOf(
                                "elevation" to AttributeValue.NumberValue(320.5),
                                "surface" to AttributeValue.TextValue("dirt"),
                                "synthetic" to AttributeValue.BooleanValue(true),
                            ),
                    ),
                ),
            instructions =
                listOf(
                    RouteInstruction(
                        text = "Walk south",
                        streetName = "Rynok Square",
                        distanceAlongRouteMeters = 0.0,
                        durationSeconds = 280.0,
                        maneuver = "1",
                    ),
                ),
            rawResponse = "{\"trip\":{}}",
        )
}
