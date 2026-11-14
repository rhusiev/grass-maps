package nl.r1a.grassmap.app

import nl.r1a.grassmap.alerts.AlertChannel
import nl.r1a.grassmap.alerts.AlertChannels
import nl.r1a.grassmap.alerts.AlertKind
import nl.r1a.grassmap.alerts.AlertSettings
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.ui.api.AlertsPageChannel
import nl.r1a.grassmap.plugin.ui.api.AlertsPageSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The app's mapping from stored alert values to the page and back (T-0413). */
class AlertsScreenMappingTest {
    @Test
    fun thePageStateMirrorsTheActiveProfilesStoredValues() {
        val state =
            alertsPageState(
                profile = RouteProfile.Bike,
                settings = AlertSettings(offRoute = true, favourite = true, poi = true),
                channels = AlertChannels(banner = true, vibration = true),
                poiKinds = setOf("amenity=cafe", "natural=spring"),
                strings = MapStrings(mapOf("route_preferences_profile_bike" to "bike")),
            )

        assertEquals("Bike", state.profileName)
        assertTrue(state.switches.offRoute)
        assertTrue(state.switches.favourite)
        assertTrue(state.switches.poi)
        assertTrue(state.channels.banner)
        assertTrue(state.channels.vibration)
        assertEquals(setOf("amenity=cafe", "natural=spring"), state.chosenPlaceKinds)
    }

    @Test
    fun everyPageSwitchMapsToTheHostKindItOwns() {
        assertEquals(AlertKind.SignalLost, AlertsPageSwitch.Signal.toAlertKind())
        assertEquals(AlertKind.Rerouted, AlertsPageSwitch.Reroute.toAlertKind())
        assertEquals(AlertKind.OffRoute, AlertsPageSwitch.OffRoute.toAlertKind())
        assertEquals(AlertKind.Arrived, AlertsPageSwitch.Arrival.toAlertKind())
        assertEquals(AlertKind.Tunnel, AlertsPageSwitch.Tunnel.toAlertKind())
        assertEquals(AlertKind.RailwayCrossing, AlertsPageSwitch.RailwayCrossing.toAlertKind())
        assertEquals(AlertKind.Hazard, AlertsPageSwitch.Hazard.toAlertKind())
        assertEquals(AlertKind.Favourite, AlertsPageSwitch.Favourite.toAlertKind())
        assertEquals(AlertKind.TrackWaypoint, AlertsPageSwitch.TrackWaypoint.toAlertKind())
    }

    @Test
    fun everyPageChannelMapsToTheHostChannel() {
        assertEquals(AlertChannel.Banner, AlertsPageChannel.Banner.toAlertChannel())
        assertEquals(AlertChannel.Sound, AlertsPageChannel.Sound.toAlertChannel())
        assertEquals(AlertChannel.Vibration, AlertsPageChannel.Vibration.toAlertChannel())
    }

    private class MapStrings(
        private val values: Map<String, String>,
    ) : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String {
            val template = values[key] ?: key
            return if (arguments.isEmpty()) template else String.format(Locale.ROOT, template, *arguments.toTypedArray())
        }

        override fun contains(key: String): Boolean = key in values
    }
}
