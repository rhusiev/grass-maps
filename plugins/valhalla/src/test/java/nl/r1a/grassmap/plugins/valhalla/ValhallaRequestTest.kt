package nl.r1a.grassmap.plugins.valhalla

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.RouteModifier
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.provider.TravelDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValhallaRequestTest {
    @Test
    fun awaypointsBecomeLocationsAndTheProfileBecomesCosting() {
        val body = parse(ValhallaRequest.build(request()))

        assertEquals("pedestrian", body.string("costing"))
        assertEquals("kilometers", body.string("units"))
        val locations = body.getValue("locations").jsonArray
        assertEquals(2, locations.size)
        assertEquals(
            49.8419,
            locations[0]
                .jsonObject
                .getValue("lat")
                .jsonPrimitive.double,
            0.0,
        )
        assertEquals(
            24.0316,
            locations[0]
                .jsonObject
                .getValue("lon")
                .jsonPrimitive.double,
            0.0,
        )
    }

    @Test
    fun aMultiplierBecomesALinearCostFactor() {
        val modifier =
            RouteModifier(
                geometry = listOf(GeoPoint(49.8419, 24.0316), GeoPoint(49.8425, 24.0325)),
                multiplier = 2.0,
                direction = TravelDirection.Forward,
            )

        val body = parse(ValhallaRequest.build(request(modifiers = listOf(modifier))))

        val factors = body.getValue("linear_cost_factors").jsonArray
        assertEquals(1, factors.size)
        assertEquals(
            2.0,
            factors[0]
                .jsonObject
                .getValue("factor")
                .jsonPrimitive.double,
            0.0,
        )
        assertEquals(
            Polyline6.encode(modifier.geometry),
            factors[0]
                .jsonObject
                .getValue("shape")
                .jsonPrimitive.content,
        )
        assertFalse(body.containsKey("exclude_polygons"))
    }

    @Test
    fun addedSecondsBecomesAFactorFromTheNominalWalkSpeed() {
        val modifier =
            RouteModifier(
                geometry = listOf(GeoPoint(49.9410, 23.9410), GeoPoint(49.9420, 23.9425)),
                addedSeconds = 45.0,
            )

        val factor =
            ValhallaRequest.factorOf(modifier, RouteProfile.Walk)
                ?: throw AssertionError("expected a factor")

        assertTrue(factor > 1.0)
        assertTrue(factor < 10.0)
    }

    @Test
    fun aForbiddenModifierBecomesAnExclusionPolygon() {
        val modifier =
            RouteModifier(
                geometry = listOf(GeoPoint(49.9410, 23.9410), GeoPoint(49.9420, 23.9425)),
                forbidden = true,
            )

        val body = parse(ValhallaRequest.build(request(modifiers = listOf(modifier))))

        val polygons = body.getValue("exclude_polygons").jsonArray
        assertEquals(1, polygons.size)
        val ring = polygons[0].jsonArray
        assertEquals(5, ring.size)
        assertEquals(ring.first(), ring.last())
        assertFalse(body.containsKey("linear_cost_factors"))
    }

    @Test
    fun theFallbackFormUsesAnExclusionForEveryModifier() {
        val modifier =
            RouteModifier(
                geometry = listOf(GeoPoint(49.9410, 23.9410), GeoPoint(49.9420, 23.9425)),
                multiplier = 1.2,
            )

        val body =
            parse(
                ValhallaRequest.build(
                    request(modifiers = listOf(modifier)),
                    useExclusionForModifiers = true,
                ),
            )

        assertFalse(body.containsKey("linear_cost_factors"))
        assertEquals(1, body.getValue("exclude_polygons").jsonArray.size)
    }

    @Test
    fun alternativesAreForwarded() {
        val body = parse(ValhallaRequest.build(request(alternatives = 2)))

        assertEquals(2, body.getValue("alternates").jsonPrimitive.int)
    }

    private fun request(
        modifiers: List<RouteModifier> = emptyList(),
        alternatives: Int = 0,
    ): RouteRequest =
        RouteRequest(
            waypoints = listOf(GeoPoint(49.8419, 24.0316), GeoPoint(49.8365, 24.0250)),
            profile = RouteProfile.Walk,
            modifiers = modifiers,
            alternatives = alternatives,
        )

    private fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private val JsonElement.jsonArray: JsonArray
        get() = this as JsonArray

    private val JsonElement.jsonPrimitive: JsonPrimitive
        get() = this as JsonPrimitive

    private val JsonElement.jsonObject: JsonObject
        get() = this as JsonObject
}

private typealias JsonElement = kotlinx.serialization.json.JsonElement
