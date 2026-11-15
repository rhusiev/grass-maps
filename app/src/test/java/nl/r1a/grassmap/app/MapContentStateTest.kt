package nl.r1a.grassmap.app

import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.navigation.NavigationState
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions the map content makes without reading the per-fix
 * navigation state: whether the navigation screen owns the map, and which
 * route the map draws. Both are read in composition, so the map screen sees
 * only their result and a fix does not recompose it.
 *
 * The recomposition count itself needs a device or an emulator and is owned by
 * the frame bench; this holds the values that count would guard.
 */
class MapContentStateTest {
    @Test
    fun anIdleSessionWithoutAFixtureLeavesTheMapToTheMapScreen() {
        assertFalse(isNavigationActive(fixture = null, state = NavigationState()))
    }

    @Test
    fun everyLivePhaseOfATripKeepsTheScreenUp() {
        for (phase in ACTIVE_PHASES) {
            val state = NavigationState(phase = phase)
            assertTrue(phase.name, isNavigationActive(fixture = null, state = state))
        }
    }

    @Test
    fun aShownFixtureOwnsTheMapWhateverTheLivePhase() {
        val fixture = NavigationScreenFixture(state = NavigationState())

        assertTrue(isNavigationActive(fixture, NavigationState()))
    }

    @Test
    fun aTripDrawsItsOwnRouteEvenWhenAPlanExists() {
        val route =
            mapRouteToDraw(
                navigationActive = true,
                activeRoute = TRIP_ROUTE,
                plannedRoute = PLAN_ROUTE,
            )

        assertEquals(TRIP_ROUTE, route)
    }

    @Test
    fun aTripWithNoRouteYetDrawsNoPlanEither() {
        assertNull(
            mapRouteToDraw(
                navigationActive = true,
                activeRoute = null,
                plannedRoute = PLAN_ROUTE,
            ),
        )
    }

    @Test
    fun afterTheTripTheMapDrawsThePlanAgain() {
        val route =
            mapRouteToDraw(
                navigationActive = false,
                activeRoute = TRIP_ROUTE,
                plannedRoute = PLAN_ROUTE,
            )

        assertEquals(PLAN_ROUTE, route)
    }

    @Test
    fun noTripAndNoPlanDrawsNothing() {
        assertNull(mapRouteToDraw(navigationActive = false, activeRoute = null, plannedRoute = null))
    }

    private companion object {
        val TRIP_ROUTE = route(60.0)
        val PLAN_ROUTE = route(120.0)
        val ACTIVE_PHASES =
            listOf(
                NavigationPhase.Following,
                NavigationPhase.Rerouting,
                NavigationPhase.Paused,
                NavigationPhase.Arrived,
            )

        /** Two routes that differ, so a wrong pick is visible in the assertion. */
        fun route(distanceMeters: Double): Route =
            Route(
                geometry = listOf(GeoPoint(49.8400, 24.0300), GeoPoint(49.8401, 24.0301)),
                distanceMeters = distanceMeters,
                durationSeconds = 43.0,
            )
    }
}
