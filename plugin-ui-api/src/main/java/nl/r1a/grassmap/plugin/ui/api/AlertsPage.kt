package nl.r1a.grassmap.plugin.ui.api

// The profile alerts page's visual vocabulary (T-0413).
//
// The app maps the active profile's alert switches, output channels and chosen
// place kinds onto these values; `ui-shell` lays the page out and resolves the
// copy. Nothing here reads the alert host or a settings file, so the page
// cannot change a switch by drawing it.

/** Every switch the alerts page draws, in its section order. */
enum class AlertsPageSwitch {
    Signal,
    Reroute,
    OffRoute,
    Arrival,
    Tunnel,
    RailwayCrossing,
    Hazard,
    Favourite,
    TrackWaypoint,
}

/** The ten alert switches and their state. */
data class AlertsPageSwitches(
    val signal: Boolean = false,
    val reroute: Boolean = false,
    val offRoute: Boolean = false,
    val arrival: Boolean = false,
    val tunnel: Boolean = false,
    val railwayCrossing: Boolean = false,
    val hazard: Boolean = false,
    val favourite: Boolean = false,
    val trackWaypoint: Boolean = false,
    val poi: Boolean = false,
) {
    /** Whether [switch] is on. */
    fun isEnabled(switch: AlertsPageSwitch): Boolean =
        when (switch) {
            AlertsPageSwitch.Signal -> signal
            AlertsPageSwitch.Reroute -> reroute
            AlertsPageSwitch.OffRoute -> offRoute
            AlertsPageSwitch.Arrival -> arrival
            AlertsPageSwitch.Tunnel -> tunnel
            AlertsPageSwitch.RailwayCrossing -> railwayCrossing
            AlertsPageSwitch.Hazard -> hazard
            AlertsPageSwitch.Favourite -> favourite
            AlertsPageSwitch.TrackWaypoint -> trackWaypoint
        }

    /** The number of switches that are on, including the implicit places switch. */
    val enabledCount: Int
        get() =
            listOf(
                signal,
                reroute,
                offRoute,
                arrival,
                tunnel,
                railwayCrossing,
                hazard,
                favourite,
                trackWaypoint,
                poi,
            ).count { it }
}

/** How an alert reaches the traveller. */
enum class AlertsPageChannel {
    Banner,
    Sound,
    Vibration,
}

/** The three output channels and their state. */
data class AlertsPageChannels(
    val banner: Boolean = false,
    val sound: Boolean = false,
    val vibration: Boolean = false,
) {
    /** Whether [channel] is on. */
    fun isEnabled(channel: AlertsPageChannel): Boolean =
        when (channel) {
            AlertsPageChannel.Banner -> banner
            AlertsPageChannel.Sound -> sound
            AlertsPageChannel.Vibration -> vibration
        }

    /** The channels that are on, in the page's order. */
    val enabled: List<AlertsPageChannel>
        get() = AlertsPageChannel.entries.filter(::isEnabled)
}

/**
 * Everything the profile alerts page draws. [profileName] is already resolved
 * in the active language ("Gravel", "bike"); the page adds the word "profile".
 * [chosenPlaceKinds] are exact search-index kinds ("amenity=drinking_water").
 * The picker row's own switch is implicit: a non-empty list turns `poi` on.
 */
data class AlertsPageState(
    val profileName: String,
    val switches: AlertsPageSwitches = AlertsPageSwitches(),
    val channels: AlertsPageChannels = AlertsPageChannels(),
    val chosenPlaceKinds: Set<String> = emptySet(),
)
