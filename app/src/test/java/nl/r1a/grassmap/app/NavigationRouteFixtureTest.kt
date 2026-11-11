package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.provider.RouteProgress
import nl.r1a.grassmap.routing.RouteJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationRouteFixtureTest {
    @Test
    fun theAcceptanceRouteDecodesAndCarriesTheInstructionModel() {
        val json =
            checkNotNull(javaClass.getResourceAsStream("/navigation/nav-route.json")) {
                "test-data/navigation/nav-route.json is missing"
            }.bufferedReader().use { reader -> reader.readText() }

        val route =
            when (val decoded = RouteJson.decode(json)) {
                is Outcome.Success -> decoded.value
                is Outcome.Failure -> throw AssertionError("the fixture does not decode: ${decoded.error}")
            }

        assertEquals(MIN_POINTS, route.geometry.size)
        assertEquals(1, route.segments.size)
        assertEquals(3, route.instructions.size)
        val progress = RouteProgress.of(route)
        assertEquals(RouteProgress.Source.Instructions, progress.source)
        assertEquals(3, progress.spanCount)
        assertTrue(progress.totalDistanceMeters > TOTAL_METERS - DISTANCE_TOLERANCE_METERS)
        assertTrue(progress.totalDistanceMeters < TOTAL_METERS + DISTANCE_TOLERANCE_METERS)
    }

    private companion object {
        const val MIN_POINTS = 70
        const val TOTAL_METERS = 382.6
        const val DISTANCE_TOLERANCE_METERS = 1.0
    }
}
