package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.ui.api.AlertsPageChannel
import nl.r1a.grassmap.plugin.ui.api.AlertsPageState
import nl.r1a.grassmap.plugin.ui.api.AlertsPageSwitch

private const val ALERTS_TITLE_KEY = "alerts_page_title"
private const val PROFILE_SUBTITLE_KEY = "alerts_page_profile_subtitle"
private const val ALERTS_BACK_KEY = "alerts_page_back"
private const val NAVIGATION_SECTION_KEY = "alerts_section_navigation"
private const val ON_THE_WAY_SECTION_KEY = "alerts_section_on_the_way"
private const val APPROACHING_SECTION_KEY = "alerts_section_approaching"
private const val REACH_SECTION_KEY = "alerts_section_reach"
private const val DONE_KEY = "alerts_page_done"

private const val OFF_ROUTE_KEY = "alerts_switch_off_route"
private const val SIGNAL_KEY = "alerts_switch_signal"
private const val REROUTE_KEY = "alerts_switch_reroute"
private const val ARRIVAL_KEY = "alerts_switch_arrival"
private const val CROSSING_KEY = "alerts_switch_crossing"
private const val TUNNEL_KEY = "alerts_switch_tunnel"
private const val HAZARD_KEY = "alerts_switch_hazard"
private const val FAVOURITE_KEY = "alerts_switch_favourite"
private const val WAYPOINT_KEY = "alerts_switch_waypoint"
private const val PLACES_KEY = "alerts_places_to_watch"
private const val PLACES_NONE_KEY = "alerts_places_none"
private const val PLACES_DESCRIPTION_KEY = "alerts_places_description"

private const val BANNER_KEY = "alerts_channel_banner"
private const val SOUND_KEY = "alerts_channel_sound"
private const val VIBRATION_KEY = "alerts_channel_vibration"

private const val SWITCH_ON_KEY = "alerts_switch_on"
private const val SWITCH_OFF_KEY = "alerts_switch_off"
private const val CHANNEL_ON_KEY = "alerts_channel_on"
private const val CHANNEL_OFF_KEY = "alerts_channel_off"

private const val PICKER_TITLE_KEY = "alerts_picker_title"
private const val PICKER_CLOSE_KEY = "alerts_picker_close"
private const val PICKER_SELECTED_KEY = "alerts_picker_selected"
private const val PICKER_NOT_SELECTED_KEY = "alerts_picker_not_selected"

private const val PROFILE_ROW_TITLE_KEY = "alerts_profile_row_title"
private const val PROFILE_ROW_VALUE_OFF_KEY = "alerts_profile_row_value_off"
private const val PROFILE_ROW_VALUE_ON_KEY = "alerts_profile_row_value_on"
private const val PROFILE_ROW_DETAIL_ON_KEY = "alerts_profile_row_detail_on"
private const val PROFILE_ROW_DETAIL_OFF_KEY = "alerts_profile_row_detail_off"
private const val PROFILE_ROW_DESCRIPTION_KEY = "alerts_profile_row_description"

private const val PLACE_DRINKING_WATER_KEY = "alerts_place_drinking_water"
private const val PLACE_SHELTER_KEY = "alerts_place_shelter"
private const val PLACE_SPRING_KEY = "alerts_place_spring"
private const val PLACE_TOILETS_KEY = "alerts_place_toilets"
private const val PLACE_PICNIC_SITE_KEY = "alerts_place_picnic_site"
private const val PLACE_VIEWPOINT_KEY = "alerts_place_viewpoint"
private const val PLACE_CAMPSITE_KEY = "alerts_place_campsite"
private const val PLACE_CAFE_KEY = "alerts_place_cafe"
private const val PLACE_PHARMACY_KEY = "alerts_place_pharmacy"

/**
 * One place kind the picker offers. [indexKind] is the exact category string
 * the search index stores ("amenity=drinking_water"); it is what the proximity
 * plugin passes to `places.pois`, never a label.
 */
internal data class AlertsPlaceKind(
    val indexKind: String,
    val labelKey: String,
)

/**
 * The kinds the place picker offers. The search index has no enumeration call,
 * so this is the app's fixed vocabulary of exact index kinds. A kind that the
 * installed extract does not carry simply finds no places.
 *
 * The first three are the design's "Drinking water, shelter, spring" row.
 */
internal val ALERT_PLACE_KINDS: List<AlertsPlaceKind> =
    listOf(
        AlertsPlaceKind("amenity=drinking_water", PLACE_DRINKING_WATER_KEY),
        AlertsPlaceKind("amenity=shelter", PLACE_SHELTER_KEY),
        AlertsPlaceKind("natural=spring", PLACE_SPRING_KEY),
        AlertsPlaceKind("amenity=toilets", PLACE_TOILETS_KEY),
        AlertsPlaceKind("tourism=picnic_site", PLACE_PICNIC_SITE_KEY),
        AlertsPlaceKind("tourism=viewpoint", PLACE_VIEWPOINT_KEY),
        AlertsPlaceKind("tourism=campsite", PLACE_CAMPSITE_KEY),
        AlertsPlaceKind("amenity=cafe", PLACE_CAFE_KEY),
        AlertsPlaceKind("amenity=pharmacy", PLACE_PHARMACY_KEY),
    )

/** One settings row; a switch row carries [switch], the places row does not. */
internal data class AlertsRowContent(
    val title: String,
    val detail: String? = null,
    val switch: AlertsPageSwitch? = null,
    val on: Boolean = false,
    val description: String,
)

internal data class AlertsSectionContent(
    val title: String,
    val rows: List<AlertsRowContent>,
)

internal data class AlertsChannelContent(
    val channel: AlertsPageChannel,
    val label: String,
    val on: Boolean,
    val description: String,
)

/** The page's resolved copy and controls. */
internal data class AlertsPageContent(
    val title: String,
    val profileSubtitle: String,
    val backDescription: String,
    val sections: List<AlertsSectionContent>,
    val reachTitle: String,
    val channels: List<AlertsChannelContent>,
    val done: String,
) {
    /** The switch rows, in drawing order; the places row is not one of them. */
    val switchRows: List<AlertsRowContent>
        get() = sections.flatMap { section -> section.rows.filter { row -> row.switch != null } }

    /** The one row that opens the place-kind picker. */
    val placesRow: AlertsRowContent
        get() = sections.flatMap { section -> section.rows }.first { row -> row.switch == null }
}

/** One kind in the place-kind picker, with its current selection. */
internal data class AlertsPlaceKindContent(
    val indexKind: String,
    val label: String,
    val selected: Boolean,
    val description: String,
)

internal data class AlertsPlacePickerContent(
    val title: String,
    val close: String,
    val kinds: List<AlertsPlaceKindContent>,
)

/** The profile editor's one alerts row (round 8: one row that opens a page). */
internal data class ProfileAlertsEntryContent(
    val title: String,
    val value: String,
    val detail: String,
    val description: String,
)

/**
 * Resolves the alerts page. The order and the three section names are the
 * design's; the nine switches are the rows that exist, and the places row is
 * the picker because its alert is implied by the chosen kinds.
 */
internal fun alertsPageContent(
    state: AlertsPageState,
    strings: Strings,
): AlertsPageContent {
    val placesDetail =
        placeKindSummary(state.chosenPlaceKinds, strings).ifEmpty { strings.resolve(PLACES_NONE_KEY) }
    return AlertsPageContent(
        title = strings.resolve(ALERTS_TITLE_KEY),
        profileSubtitle = strings.resolve(PROFILE_SUBTITLE_KEY, listOf(state.profileName)),
        backDescription = strings.resolve(ALERTS_BACK_KEY),
        sections =
            listOf(
                AlertsSectionContent(
                    title = strings.resolve(NAVIGATION_SECTION_KEY),
                    rows =
                        listOf(
                            switchRow(AlertsPageSwitch.OffRoute, OFF_ROUTE_KEY, state, strings),
                            switchRow(AlertsPageSwitch.Signal, SIGNAL_KEY, state, strings),
                            switchRow(AlertsPageSwitch.Reroute, REROUTE_KEY, state, strings),
                            switchRow(AlertsPageSwitch.Arrival, ARRIVAL_KEY, state, strings),
                        ),
                ),
                AlertsSectionContent(
                    title = strings.resolve(ON_THE_WAY_SECTION_KEY),
                    rows =
                        listOf(
                            switchRow(AlertsPageSwitch.RailwayCrossing, CROSSING_KEY, state, strings),
                            switchRow(AlertsPageSwitch.Tunnel, TUNNEL_KEY, state, strings),
                            switchRow(AlertsPageSwitch.Hazard, HAZARD_KEY, state, strings),
                        ),
                ),
                AlertsSectionContent(
                    title = strings.resolve(APPROACHING_SECTION_KEY),
                    rows =
                        listOf(
                            switchRow(AlertsPageSwitch.Favourite, FAVOURITE_KEY, state, strings),
                            switchRow(AlertsPageSwitch.TrackWaypoint, WAYPOINT_KEY, state, strings),
                            AlertsRowContent(
                                title = strings.resolve(PLACES_KEY),
                                detail = placesDetail,
                                description =
                                    strings.resolve(
                                        PLACES_DESCRIPTION_KEY,
                                        listOf(strings.resolve(PLACES_KEY), placesDetail),
                                    ),
                            ),
                        ),
                ),
            ),
        reachTitle = strings.resolve(REACH_SECTION_KEY),
        channels =
            AlertsPageChannel.entries.map { channel ->
                val on = state.channels.isEnabled(channel)
                val label = strings.resolve(channelKey(channel))
                AlertsChannelContent(
                    channel = channel,
                    label = label,
                    on = on,
                    description = strings.resolve(if (on) CHANNEL_ON_KEY else CHANNEL_OFF_KEY, listOf(label)),
                )
            },
        done = strings.resolve(DONE_KEY),
    )
}

/** The picker's kinds in catalog order, with the chosen ones marked. */
internal fun alertsPlacePickerContent(
    chosenKinds: Set<String>,
    strings: Strings,
): AlertsPlacePickerContent =
    AlertsPlacePickerContent(
        title = strings.resolve(PICKER_TITLE_KEY),
        close = strings.resolve(PICKER_CLOSE_KEY),
        kinds =
            ALERT_PLACE_KINDS.map { kind ->
                val label = strings.resolve(kind.labelKey)
                val selected = kind.indexKind in chosenKinds
                AlertsPlaceKindContent(
                    indexKind = kind.indexKind,
                    label = label,
                    selected = selected,
                    description =
                        strings.resolve(
                            if (selected) PICKER_SELECTED_KEY else PICKER_NOT_SELECTED_KEY,
                            listOf(label),
                        ),
                )
            },
    )

/**
 * The profile editor's alerts row. Its value counts the alert switches and its
 * detail names the outputs, as the round 8 editor row does ("all off",
 * "Banner, sound and vibration are off").
 */
internal fun profileAlertsEntryContent(
    state: AlertsPageState,
    strings: Strings,
): ProfileAlertsEntryContent {
    val enabled = state.switches.enabledCount
    val value =
        if (enabled == 0) {
            strings.resolve(PROFILE_ROW_VALUE_OFF_KEY)
        } else {
            strings.resolve(PROFILE_ROW_VALUE_ON_KEY, listOf(enabled.toString()))
        }
    val detail =
        if (state.channels.enabled.isEmpty()) {
            strings.resolve(PROFILE_ROW_DETAIL_OFF_KEY)
        } else {
            strings.resolve(
                PROFILE_ROW_DETAIL_ON_KEY,
                listOf(state.channels.enabled.joinToString(", ") { channel -> strings.resolve(channelKey(channel)) }),
            )
        }
    return ProfileAlertsEntryContent(
        title = strings.resolve(PROFILE_ROW_TITLE_KEY),
        value = value,
        detail = detail,
        description = strings.resolve(PROFILE_ROW_DESCRIPTION_KEY, listOf(value, detail)),
    )
}

private fun switchRow(
    switch: AlertsPageSwitch,
    labelKey: String,
    state: AlertsPageState,
    strings: Strings,
): AlertsRowContent {
    val label = strings.resolve(labelKey)
    val on = state.switches.isEnabled(switch)
    return AlertsRowContent(
        title = label,
        switch = switch,
        on = on,
        description = strings.resolve(if (on) SWITCH_ON_KEY else SWITCH_OFF_KEY, listOf(label)),
    )
}

private fun placeKindSummary(
    chosenKinds: Set<String>,
    strings: Strings,
): String =
    ALERT_PLACE_KINDS
        .filter { kind -> kind.indexKind in chosenKinds }
        .mapIndexed { index, kind ->
            val label = strings.resolve(kind.labelKey)
            if (index == 0) label else label.replaceFirstChar { character -> character.lowercase() }
        }.joinToString(", ")

private fun channelKey(channel: AlertsPageChannel): String =
    when (channel) {
        AlertsPageChannel.Banner -> BANNER_KEY
        AlertsPageChannel.Sound -> SOUND_KEY
        AlertsPageChannel.Vibration -> VIBRATION_KEY
    }
