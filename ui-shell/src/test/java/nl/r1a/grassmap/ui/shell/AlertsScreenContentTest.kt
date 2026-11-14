package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.ui.api.AlertsPageChannel
import nl.r1a.grassmap.plugin.ui.api.AlertsPageChannels
import nl.r1a.grassmap.plugin.ui.api.AlertsPageState
import nl.r1a.grassmap.plugin.ui.api.AlertsPageSwitch
import nl.r1a.grassmap.plugin.ui.api.AlertsPageSwitches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The profile alerts page's content model (T-0413). */
class AlertsScreenContentTest {
    @Test
    fun thePageHasTheThreeSectionsAndTheNineSwitches() {
        val content = alertsPageContent(STATE, MapStrings(ENGLISH))

        assertEquals("Alerts", content.title)
        assertEquals(
            listOf("Navigation", "On the way", "Approaching"),
            content.sections.map { section -> section.title },
        )
        assertEquals(
            listOf("Off route", "GPS signal lost or back", "Route recalculated", "Arrival"),
            content.sections[0].rows.map { row -> row.title },
        )
        assertEquals(
            listOf("Railway crossings", "Tunnels", "Hazards"),
            content.sections[1].rows.map { row -> row.title },
        )
        assertEquals(
            listOf("Saved places", "Track waypoints", "Places to watch"),
            content.sections[2].rows.map { row -> row.title },
        )
        assertEquals(9, content.switchRows.size)
        assertEquals(
            listOf(
                AlertsPageSwitch.OffRoute,
                AlertsPageSwitch.Signal,
                AlertsPageSwitch.Reroute,
                AlertsPageSwitch.Arrival,
                AlertsPageSwitch.RailwayCrossing,
                AlertsPageSwitch.Tunnel,
                AlertsPageSwitch.Hazard,
                AlertsPageSwitch.Favourite,
                AlertsPageSwitch.TrackWaypoint,
            ),
            content.switchRows.map { row -> row.switch },
        )
        assertTrue("the places row is the picker, not a switch", content.placesRow.switch == null)
    }

    @Test
    fun theProfileNameIsTheSubtitle() {
        val content = alertsPageContent(STATE.copy(profileName = "Gravel"), MapStrings(ENGLISH))

        assertEquals("Gravel profile", content.profileSubtitle)
    }

    @Test
    fun thePlacesRowDetailsTheChosenKindsInTheDesignsOrder() {
        val content = alertsPageContent(STATE, MapStrings(ENGLISH))

        assertEquals("Places to watch", content.placesRow.title)
        assertEquals("Drinking water, shelter, spring", content.placesRow.detail)
    }

    @Test
    fun anEmptyPlaceChoiceSaysSo() {
        val content = alertsPageContent(STATE.copy(chosenPlaceKinds = emptySet()), MapStrings(ENGLISH))

        assertEquals("No place kinds chosen", content.placesRow.detail)
    }

    @Test
    fun theThreeReachChannelsAreDrawnWithTheirState() {
        val state =
            STATE.copy(
                channels = AlertsPageChannels(banner = true, sound = false, vibration = true),
            )

        val content = alertsPageContent(state, MapStrings(ENGLISH))

        assertEquals("How alerts reach you", content.reachTitle)
        assertEquals(
            listOf(AlertsPageChannel.Banner, AlertsPageChannel.Sound, AlertsPageChannel.Vibration),
            content.channels.map { channel -> channel.channel },
        )
        assertEquals(listOf(true, false, true), content.channels.map { channel -> channel.on })
        assertEquals("Banner, on", content.channels.first().description)
        assertEquals("Sound, off", content.channels[1].description)
    }

    @Test
    fun thePickerOffersTheIndexKindsAndMarksTheChosenOnes() {
        val picker = alertsPlacePickerContent(setOf("amenity=drinking_water", "natural=spring"), MapStrings(ENGLISH))

        assertEquals("Places to watch", picker.title)
        assertEquals(9, picker.kinds.size)
        assertEquals("amenity=drinking_water", picker.kinds.first().indexKind)
        assertEquals(
            listOf(
                "amenity=drinking_water",
                "amenity=shelter",
                "natural=spring",
                "amenity=toilets",
                "tourism=picnic_site",
                "tourism=viewpoint",
                "tourism=campsite",
                "amenity=cafe",
                "amenity=pharmacy",
            ),
            picker.kinds.map { kind -> kind.indexKind },
        )
        assertTrue(picker.kinds.first().selected)
        assertFalse(picker.kinds[1].selected)
        assertTrue(picker.kinds[2].selected)
        assertEquals("Drinking water, selected", picker.kinds.first().description)
    }

    @Test
    fun theProfileEditorRowSaysAllOffWithTheOffChannels() {
        val row = profileAlertsEntryContent(STATE.copy(switches = AlertsPageSwitches()), MapStrings(ENGLISH))

        assertEquals("Alerts", row.title)
        assertEquals("all off", row.value)
        assertEquals("Banner, sound and vibration are off", row.detail)
        assertEquals("all off. Banner, sound and vibration are off.", row.description)
    }

    @Test
    fun theProfileEditorRowCountsTheSwitchesAndNamesTheOutputs() {
        val state =
            STATE.copy(
                switches = AlertsPageSwitches(arrival = true, hazard = true),
                channels = AlertsPageChannels(banner = true, vibration = true),
            )

        val row = profileAlertsEntryContent(state, MapStrings(ENGLISH))

        assertEquals("2 on", row.value)
        assertEquals("Outputs: Banner, Vibration", row.detail)
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

    private companion object {
        val STATE =
            AlertsPageState(
                profileName = "bike",
                switches = AlertsPageSwitches(offRoute = true, favourite = true, poi = true),
                channels = AlertsPageChannels(banner = false, sound = false, vibration = false),
                chosenPlaceKinds = setOf("amenity=drinking_water", "amenity=shelter", "natural=spring"),
            )

        val ENGLISH =
            mapOf(
                "alerts_page_title" to "Alerts",
                "alerts_page_profile_subtitle" to "%1\$s profile",
                "alerts_page_back" to "Back",
                "alerts_section_navigation" to "Navigation",
                "alerts_section_on_the_way" to "On the way",
                "alerts_section_approaching" to "Approaching",
                "alerts_section_reach" to "How alerts reach you",
                "alerts_page_done" to "Done",
                "alerts_switch_off_route" to "Off route",
                "alerts_switch_signal" to "GPS signal lost or back",
                "alerts_switch_reroute" to "Route recalculated",
                "alerts_switch_arrival" to "Arrival",
                "alerts_switch_crossing" to "Railway crossings",
                "alerts_switch_tunnel" to "Tunnels",
                "alerts_switch_hazard" to "Hazards",
                "alerts_switch_favourite" to "Saved places",
                "alerts_switch_waypoint" to "Track waypoints",
                "alerts_places_to_watch" to "Places to watch",
                "alerts_places_none" to "No place kinds chosen",
                "alerts_places_description" to "%1\$s. %2\$s. Opens the place-kind picker.",
                "alerts_channel_banner" to "Banner",
                "alerts_channel_sound" to "Sound",
                "alerts_channel_vibration" to "Vibration",
                "alerts_switch_on" to "%1\$s, on",
                "alerts_switch_off" to "%1\$s, off",
                "alerts_channel_on" to "%1\$s, on",
                "alerts_channel_off" to "%1\$s, off",
                "alerts_picker_title" to "Places to watch",
                "alerts_picker_close" to "Close",
                "alerts_picker_selected" to "%1\$s, selected",
                "alerts_picker_not_selected" to "%1\$s, not selected",
                "alerts_profile_row_title" to "Alerts",
                "alerts_profile_row_value_off" to "all off",
                "alerts_profile_row_value_on" to "%1\$s on",
                "alerts_profile_row_detail_on" to "Outputs: %1\$s",
                "alerts_profile_row_detail_off" to "Banner, sound and vibration are off",
                "alerts_profile_row_description" to "%1\$s. %2\$s.",
                "alerts_place_drinking_water" to "Drinking water",
                "alerts_place_shelter" to "Shelter",
                "alerts_place_spring" to "Spring",
                "alerts_place_toilets" to "Toilets",
                "alerts_place_picnic_site" to "Picnic site",
                "alerts_place_viewpoint" to "Viewpoint",
                "alerts_place_campsite" to "Campsite",
                "alerts_place_cafe" to "Cafe",
                "alerts_place_pharmacy" to "Pharmacy",
            )
    }
}
