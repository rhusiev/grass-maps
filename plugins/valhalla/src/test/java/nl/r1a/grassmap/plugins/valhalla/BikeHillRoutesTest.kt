package nl.r1a.grassmap.plugins.valhalla

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The T-0308 acceptance evidence: ten fixed bicycle routes in Lviv oblast,
 * each climbed with the tuned profile and with full hill avoidance. The ascent
 * is recomputed from the committed height samples, so the comparison is a
 * property of the fixture and not a number copied from a run.
 */
class BikeHillRoutesTest {
    @Test
    fun tenBikeRoutesClimbLessWithFullAvoidance() {
        val document = resource("/routing/hills/bike-hills.json")
        val routes = document.getValue("routes").jsonArray

        assertEquals(10, routes.size)
        for (element in routes) {
            val route = element.jsonObject
            val name = route.string("name")
            val tuned = route.getValue("default").jsonObject
            val avoiding = route.getValue("avoidHills").jsonObject
            val tunedAscent = ascent(tuned)
            val avoidingAscent = ascent(avoiding)

            assertEquals("$name: recorded tuned ascent", tuned.double("ascentMeters"), tunedAscent, 0.6)
            assertEquals("$name: recorded avoid ascent", avoiding.double("ascentMeters"), avoidingAscent, 0.6)
            assertTrue(
                "$name: full avoidance must lower the ascent ($tunedAscent -> $avoidingAscent)",
                avoidingAscent < tunedAscent,
            )
            assertTrue(
                "$name: no height samples",
                tuned
                    .getValue("samples")
                    .jsonPrimitive.content
                    .count { it == ',' } > 10,
            )
        }
    }

    @Test
    fun theFixtureRecordsTheCostingValuesItWasMeasuredWith() {
        val document = resource("/routing/hills/bike-hills.json")

        assertEquals("bike", document.string("profile"))
        assertEquals(0.4, document.double("defaultUseHills"), 0.0)
        assertEquals(0.0, document.double("avoidHillsUseHills"), 0.0)
        assertEquals(25.0, document.double("samplingMetres"), 0.0)
    }

    private fun ascent(variant: JsonObject): Double {
        val heights =
            variant
                .getValue("samples")
                .jsonPrimitive.content
                .split(',')
                .map { it.toDouble() }
        return heights.zipWithNext().sumOf { (earlier, later) -> maxOf(0.0, later - earlier) }
    }

    private fun resource(name: String): JsonObject =
        checkNotNull(javaClass.getResourceAsStream(name)) { "missing $name" }
            .bufferedReader()
            .use { Json.parseToJsonElement(it.readText()).jsonObject }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.double(key: String): Double = getValue(key).jsonPrimitive.doubleOrNull ?: error("$key is not a number")

    private val kotlinx.serialization.json.JsonElement.jsonPrimitive: JsonPrimitive
        get() = this as JsonPrimitive
}
