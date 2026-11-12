package nl.r1a.grassmap.app

import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.navigation.NavigationState
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugins.way.alerts.WayAlertPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WayAlertWiringTest {
    private val route =
        Route(
            geometry = listOf(GeoPoint(49.949, 23.943), GeoPoint(49.949, 23.953)),
            distanceMeters = 720.0,
            durationSeconds = 120.0,
        )

    @Test
    fun everyNavigationPhaseMapsToItsWayAlertPhase() {
        val expected =
            mapOf(
                NavigationPhase.Idle to WayAlertPhase.Idle,
                NavigationPhase.Following to WayAlertPhase.Following,
                NavigationPhase.Rerouting to WayAlertPhase.Rerouting,
                NavigationPhase.Paused to WayAlertPhase.Paused,
                NavigationPhase.Arrived to WayAlertPhase.Arrived,
                NavigationPhase.Stopped to WayAlertPhase.Stopped,
            )

        for ((navigation, wayAlert) in expected) {
            assertEquals(wayAlert, NavigationState(phase = navigation).toWayAlertState().phase)
        }
    }

    @Test
    fun theAlongDistanceIsTheRouteTotalMinusTheRemainingDistance() {
        val fix = LocationFix(position = GeoPoint(49.949, 23.949), accuracyMeters = 5.0, timeMillis = 1_700_000_000_000L)
        val state =
            NavigationState(
                phase = NavigationPhase.Following,
                fix = fix,
                activeRoute = route,
                routeRevision = 4L,
                remainingDistanceMeters = 220.0,
            )

        val mapped = state.toWayAlertState()

        assertEquals(500.0, mapped.alongRouteMeters, 0.001)
        assertEquals(4L, mapped.routeRevision)
        assertEquals(route, mapped.route)
        assertEquals(1_700_000_000_000L, mapped.fixTimeMillis)
    }

    @Test
    fun aStateWithoutARouteCarriesNoAlongDistance() {
        val mapped = NavigationState(phase = NavigationPhase.Idle).toWayAlertState()

        assertEquals(0.0, mapped.alongRouteMeters, 0.001)
        assertNull(mapped.route)
    }
}
