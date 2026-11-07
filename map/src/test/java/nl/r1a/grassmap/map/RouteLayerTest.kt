package nl.r1a.grassmap.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import nl.r1a.grassmap.map.style.toFeatureCollection
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.StyleId
import nl.r1a.grassmap.plugin.api.model.StyleSpec
import nl.r1a.grassmap.plugin.api.provider.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteLayerTest {
    @Test
    fun theRouteBecomesOneLineStringWithLongitudeFirst() {
        val route = route()

        val root = Json.parseToJsonElement(route.toFeatureCollection()).jsonObject

        assertEquals("FeatureCollection", root.getValue("type").toStringValue())
        val feature = (root.getValue("features") as JsonArray).single().jsonObject
        val geometry = feature.getValue("geometry") as JsonObject
        assertEquals("LineString", geometry.getValue("type").toStringValue())
        val coordinates = geometry["coordinates"] as JsonArray
        assertEquals(2, coordinates.size)
        val first = coordinates[0] as JsonArray
        assertEquals(24.0316, (first[0] as JsonPrimitive).double, 0.0)
        assertEquals(49.8419, (first[1] as JsonPrimitive).double, 0.0)
    }

    @Test
    fun theHostStateKeepsAndClearsTheRoute() {
        val state =
            MapHostState(
                StyleSpec(StyleId("standard"), """{"version":8}"""),
                Camera(center = GeoPoint(49.84, 24.03), zoom = 12.0),
            ) { null }
        val route = route()

        state.updateRoute(route)
        assertEquals(route, state.route.value)

        state.updateRoute(null)
        assertNull(state.route.value)
    }

    private fun route(): Route =
        Route(
            geometry =
                listOf(
                    GeoPoint(49.8419, 24.0316),
                    GeoPoint(49.8365, 24.0250),
                ),
            distanceMeters = 1_009.0,
            durationSeconds = 716.27,
        )

    private fun kotlinx.serialization.json.JsonElement.toStringValue(): String = (this as JsonPrimitive).content
}
