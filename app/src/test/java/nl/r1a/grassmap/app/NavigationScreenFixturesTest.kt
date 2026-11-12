package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.routing.RouteJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The T-0320 screen fixtures decode with the production route reader and carry
 * the two legs the next-stop readout needs. A fixture that silently lost a
 * segment would otherwise make the goldens and the live replay show a blank
 * stop chip without failing anything.
 */
class NavigationScreenFixturesTest {
    @Test
    fun bothScreenRoutesDecodeWithTwoLegsAndInstructions() {
        for (name in FIXTURES) {
            val route = decode(name)

            assertTrue("$name has no instructions", route.instructions.isNotEmpty())
            assertEquals("$name must have two legs", 2, route.segments.size)
            val legs = route.segments.sumOf { it.distanceMeters }
            assertTrue(
                "$name legs sum to $legs, route is ${route.distanceMeters}",
                abs(legs - route.distanceMeters) <= LEG_TOLERANCE_METERS,
            )
        }
    }

    private fun decode(name: String): Route {
        val json =
            requireNotNull(javaClass.getResourceAsStream("/navigation/$name")) {
                "$name is missing from the test resources"
            }.bufferedReader().use { it.readText() }
        return when (val outcome = RouteJson.decode(json)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("$name did not decode: ${outcome.error}")
        }
    }

    private companion object {
        val FIXTURES = listOf("screen-route.json", "screen-live-route.json")
        const val LEG_TOLERANCE_METERS = 0.5
    }
}
