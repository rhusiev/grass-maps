package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.alerts.AlertChannel
import nl.r1a.grassmaps.alerts.AlertChannels
import nl.r1a.grassmaps.alerts.AlertKind
import nl.r1a.grassmaps.alerts.AlertSettings
import nl.r1a.grassmaps.plugin.api.provider.RouteProfile
import nl.r1a.grassmaps.plugin.api.service.Strings
import nl.r1a.grassmaps.plugin.ui.api.AlertsPageChannel
import nl.r1a.grassmaps.plugin.ui.api.AlertsPageChannels
import nl.r1a.grassmaps.plugin.ui.api.AlertsPageState
import nl.r1a.grassmaps.plugin.ui.api.AlertsPageSwitch
import nl.r1a.grassmaps.plugin.ui.api.AlertsPageSwitches
import nl.r1a.grassmaps.ui.shell.profileLabel

/**
 * Maps the active profile's stored alert values onto the page's vocabulary
 * (T-0413). The page never reads [AlertSettings] itself, so this is the only
 * place the two naming sets meet.
 */
internal fun alertsPageState(
    profile: RouteProfile,
    settings: AlertSettings,
    channels: AlertChannels,
    poiKinds: Set<String>,
    strings: Strings,
): AlertsPageState =
    AlertsPageState(
        profileName = profileLabel(profile, strings).replaceFirstChar { character -> character.titlecase() },
        switches =
            AlertsPageSwitches(
                signal = settings.signal,
                reroute = settings.reroute,
                offRoute = settings.offRoute,
                arrival = settings.arrival,
                tunnel = settings.tunnel,
                railwayCrossing = settings.railwayCrossing,
                hazard = settings.hazard,
                favourite = settings.favourite,
                trackWaypoint = settings.trackWaypoint,
                poi = settings.poi,
            ),
        channels =
            AlertsPageChannels(
                banner = channels.banner,
                sound = channels.sound,
                vibration = channels.vibration,
            ),
        chosenPlaceKinds = poiKinds,
    )

/** The alert kind a page switch owns. */
internal fun AlertsPageSwitch.toAlertKind(): AlertKind =
    when (this) {
        AlertsPageSwitch.Signal -> AlertKind.SignalLost
        AlertsPageSwitch.Reroute -> AlertKind.Rerouted
        AlertsPageSwitch.OffRoute -> AlertKind.OffRoute
        AlertsPageSwitch.Arrival -> AlertKind.Arrived
        AlertsPageSwitch.Tunnel -> AlertKind.Tunnel
        AlertsPageSwitch.RailwayCrossing -> AlertKind.RailwayCrossing
        AlertsPageSwitch.Hazard -> AlertKind.Hazard
        AlertsPageSwitch.Favourite -> AlertKind.Favourite
        AlertsPageSwitch.TrackWaypoint -> AlertKind.TrackWaypoint
    }

/** The alert host's channel for a page reach control. */
internal fun AlertsPageChannel.toAlertChannel(): AlertChannel =
    when (this) {
        AlertsPageChannel.Banner -> AlertChannel.Banner
        AlertsPageChannel.Sound -> AlertChannel.Sound
        AlertsPageChannel.Vibration -> AlertChannel.Vibration
    }
