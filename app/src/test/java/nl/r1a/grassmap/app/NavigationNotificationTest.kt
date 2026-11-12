package nl.r1a.grassmap.app

import nl.r1a.grassmap.navigation.NavigationInstruction
import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.navigation.NavigationState
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class NavigationNotificationTest {
    @Test
    fun theTurnIsTheTitleAndItsDistanceIsTheText() {
        val content = content(NavigationState(phase = NavigationPhase.Following, nextInstruction = TURN, activeRoute = ROUTE))

        assertEquals("Turn left", content.title)
        assertEquals("250 m", content.text)
        assertEquals(false, content.paused)
    }

    @Test
    fun aLongDistanceReadsInKilometres() {
        val content =
            content(
                NavigationState(
                    phase = NavigationPhase.Rerouting,
                    nextInstruction = TURN.copy(distanceToInstructionMeters = 1_234.0),
                    activeRoute = ROUTE,
                ),
            )

        assertEquals("1.2 km", content.text)
    }

    @Test
    fun withoutATurnTheRouteRemainingDistanceIsShown() {
        val content =
            content(
                NavigationState(
                    phase = NavigationPhase.Following,
                    remainingDistanceMeters = 80.0,
                    activeRoute = ROUTE,
                ),
            )

        assertEquals("Following the route", content.title)
        assertEquals("80 m", content.text)
    }

    @Test
    fun pausedKeepsTheTurnAndSaysPaused() {
        val content = content(NavigationState(phase = NavigationPhase.Paused, nextInstruction = TURN, activeRoute = ROUTE))

        assertEquals("Turn left", content.title)
        assertEquals("Navigation paused", content.text)
        assertEquals(true, content.paused)
    }

    @Test
    fun arrivalAndIdleHaveTheirOwnText() {
        val arrived = content(NavigationState(phase = NavigationPhase.Arrived, activeRoute = ROUTE))
        val idle = content(NavigationState())
        val stopped = content(NavigationState(phase = NavigationPhase.Stopped))

        assertEquals("You have arrived", arrived.title)
        assertNull(arrived.text)
        assertEquals("Starting navigation", idle.title)
        assertEquals("Navigation stopped", stopped.title)
    }

    @Test
    fun theNotificationOffersStopAloneAfterArrival() {
        assertEquals(
            listOf(NavigationNotificationAction.Stop),
            navigationNotificationActions(NavigationPhase.Arrived),
        )
        assertEquals(
            listOf(NavigationNotificationAction.Resume, NavigationNotificationAction.Stop),
            navigationNotificationActions(NavigationPhase.Paused),
        )
        assertEquals(
            listOf(NavigationNotificationAction.Pause, NavigationNotificationAction.Stop),
            navigationNotificationActions(NavigationPhase.Following),
        )
    }

    private fun content(state: NavigationState): NavigationNotificationContent =
        navigationNotificationContent(state, TestStrings, Locale.ROOT)

    private object TestStrings : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String =
            when (key) {
                NavigationNotificationStrings.STARTING -> "Starting navigation"
                NavigationNotificationStrings.FOLLOWING -> "Following the route"
                NavigationNotificationStrings.ARRIVED -> "You have arrived"
                NavigationNotificationStrings.PAUSED -> "Navigation paused"
                NavigationNotificationStrings.STOPPED -> "Navigation stopped"
                "map_distance_metres" -> "${arguments.first()} m"
                "map_distance_kilometres" -> "${arguments.first()} km"
                else -> key
            }

        override fun contains(key: String): Boolean = true
    }

    private companion object {
        val ROUTE =
            Route(
                geometry = listOf(GeoPoint(49.8400, 24.0300), GeoPoint(49.8420, 24.0300)),
                distanceMeters = 222.0,
                durationSeconds = 200.0,
            )
        val TURN = NavigationInstruction(text = "Turn left", distanceToInstructionMeters = 250.0)
    }
}
