package nl.r1a.grassmap.plugin.ui.api

// The navigation screen's visual vocabulary (T-0320).
//
// The app maps one published `NavigationState`, the guidance panel, the speed
// streams and the active alert banners onto these values; `ui-shell` lays the
// screen out and `plugins/guidance-ui` draws the turn card. Nothing here reads
// the follower, the camera or the clock, so a screen frame cannot recompute
// guidance.

/** Which lifecycle card the screen is in. Off route is a relation, not a phase. */
enum class NavigationScreenStatus {
    Following,
    OffRoute,
    Paused,
    Arrived,
}

/** The turn arrow the card draws. */
enum class NavigationTurnArrow {
    Straight,
    SlightLeft,
    Left,
    SharpLeft,
    Uturn,
    SlightRight,
    Right,
    SharpRight,
    Roundabout,
    Arrive,
    Reroute,
    Pause,
}

/**
 * The read-only turn card at the top.
 *
 * [distance] and [street] are the next turn when [title] is null. A state card
 * ([NavigationScreenStatus.OffRoute], [NavigationScreenStatus.Paused],
 * [NavigationScreenStatus.Arrived]) puts its own [title] and [detail] there
 * instead and keeps the turn facts out of the card.
 */
data class NavigationTurnCard(
    val status: NavigationScreenStatus = NavigationScreenStatus.Following,
    val distance: String = "",
    val street: String = "",
    val arrow: NavigationTurnArrow = NavigationTurnArrow.Straight,
    val thenStreet: String? = null,
    val thenArrow: NavigationTurnArrow? = null,
    val title: String? = null,
    val detail: String? = null,
    val strip: String? = null,
    val dimmed: Boolean = false,
)

/** The two read-only glass chips under the turn card. */
data class NavigationReadout(
    val stopDistance: String? = null,
    val stopTime: String? = null,
    val stopName: String? = null,
    val speedKmh: String? = null,
)

/** The trip and speed facts the expanded sheet shows. */
data class NavigationTripSummary(
    val destination: String,
    val remainingDistance: String,
    val arrivalTime: String?,
    val remainingTime: String,
    val profile: String = "walk",
    val speedCurrent: String? = null,
    val speedAverage: String? = null,
    val speedMax: String? = null,
)

/** How a banner reads; the screen owns the colours. */
enum class NavigationAlertTone {
    Info,
    Warning,
}

/** The pictogram a banner draws. */
enum class NavigationAlertGlyph {
    Crossing,
    Tunnel,
    Hazard,
    Ford,
    Reroute,
    Signal,
    Arrival,
    Place,
}

/** One banner, with its copy already resolved for the active language. */
data class NavigationScreenAlert(
    val tone: NavigationAlertTone,
    val glyph: NavigationAlertGlyph,
    val title: String,
    val detail: String? = null,
)

/** The three camera switches for this trip. */
data class NavigationCameraSwitches(
    val autoZoom: Boolean = true,
    val autoCentre: Boolean = true,
    val tilt: Boolean = true,
)

/** The seven alert switches for this trip. */
data class NavigationAlertSwitches(
    val signal: Boolean = false,
    val reroute: Boolean = false,
    val offRoute: Boolean = false,
    val arrival: Boolean = false,
    val tunnel: Boolean = false,
    val crossing: Boolean = false,
    val hazard: Boolean = false,
) {
    /** The number of switches that are on, for the sheet's section label. */
    val enabledCount: Int
        get() =
            listOf(signal, reroute, offRoute, arrival, tunnel, crossing, hazard)
                .count { it }
}

/** Everything the navigation screen draws, derived once per published state. */
data class NavigationScreenState(
    val status: NavigationScreenStatus = NavigationScreenStatus.Following,
    val turn: NavigationTurnCard = NavigationTurnCard(),
    val readout: NavigationReadout = NavigationReadout(),
    val trip: NavigationTripSummary =
        NavigationTripSummary(
            destination = "",
            remainingDistance = "",
            arrivalTime = null,
            remainingTime = "",
        ),
    val alerts: List<NavigationScreenAlert> = emptyList(),
    val camera: NavigationCameraSwitches = NavigationCameraSwitches(),
    val alertSwitches: NavigationAlertSwitches = NavigationAlertSwitches(),
    val recentreVisible: Boolean = false,
    val signalLost: Boolean = false,
)

/**
 * The round 5 colours the turn card and the shell share. Values are ARGB longs
 * so this contract keeps no Compose type.
 */
object NavigationScreenColors {
    const val GREEN: Long = 0xFF1E7F53
    const val GREEN_STRIP: Long = 0xFF145C3B
    const val GREEN_INK: Long = 0xFFD7EFE2
    const val OFF_ROUTE: Long = 0xFF8A5A00
    const val OFF_ROUTE_STRIP: Long = 0xFF6B4600
    const val PAUSED: Long = 0xFF5F6368
    const val PAUSED_STRIP: Long = 0xFF494D52
    const val DANGER: Long = 0xFFB3261E
    const val DANGER_SOFT: Long = 0xFFFDECEA
    const val WARNING_BG: Long = 0xFFFFF8E1
    const val WARNING_LINE: Long = 0xFFF2B200
    const val WARNING_INK: Long = 0xFF5C4300
    const val WARNING_SUB: Long = 0xFF7A5D00
    const val INFO_BG: Long = 0xFFEAF2FD
    const val INFO_LINE: Long = 0xFF8AB4F8
    const val INFO_INK: Long = 0xFF123A6B
    const val INFO_SUB: Long = 0xFF3D5F8C
}
