package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.ui.api.NavigationScreenState
import nl.r1a.grassmap.plugin.ui.api.NavigationScreenStatus

/** Which part of the navigation chrome is open. */
enum class NavigationSheet {
    Collapsed,
    Trip,
    Settings,
}

/** Every control the navigation screen can show, for reachability tests. */
enum class NavigationControl {
    End,
    Eta,
    Expand,
    Collapse,
    Recentre,
    CameraAndAlerts,
    Back,
    ResetToProfile,
    AutoZoom,
    AutoCentre,
    Tilt,
    AlertSignal,
    AlertReroute,
    AlertOffRoute,
    AlertArrival,
    AlertTunnel,
    AlertCrossing,
    AlertHazard,
    Pause,
    Resume,
    Done,
}

/** The alert switches the settings sheet exposes, one per `AlertSettings` field. */
enum class NavigationAlertSwitch {
    Signal,
    Reroute,
    OffRoute,
    Arrival,
    Tunnel,
    Crossing,
    Hazard,
}

/**
 * The controls reachable in one screen state.
 *
 * The composable reads this set before drawing a control, so the test and the
 * screen cannot disagree about what is reachable. Arrival is its own state with
 * one action: End, Pause, the ETA and the sheet are gone with the trip.
 */
fun navigationControls(
    state: NavigationScreenState,
    sheet: NavigationSheet,
): Set<NavigationControl> {
    val controls = mutableSetOf<NavigationControl>()
    when (state.status) {
        NavigationScreenStatus.Arrived -> {
            controls += NavigationControl.Done
            return controls
        }

        NavigationScreenStatus.Following,
        NavigationScreenStatus.OffRoute,
        NavigationScreenStatus.Paused,
        -> {
            Unit
        }
    }
    when (sheet) {
        NavigationSheet.Collapsed -> {
            controls += NavigationControl.End
            controls += NavigationControl.Expand
            if (state.recentreVisible) {
                controls += NavigationControl.Recentre
            }
            if (state.status == NavigationScreenStatus.Paused) {
                controls += NavigationControl.Resume
            } else {
                controls += NavigationControl.Eta
            }
        }

        NavigationSheet.Trip -> {
            controls += NavigationControl.Collapse
            controls += NavigationControl.CameraAndAlerts
            controls += NavigationControl.End
            if (state.status == NavigationScreenStatus.Paused) {
                controls += NavigationControl.Resume
            } else {
                controls += NavigationControl.Pause
            }
        }

        NavigationSheet.Settings -> {
            controls += NavigationControl.Back
            controls += NavigationControl.ResetToProfile
            controls += NavigationControl.AutoZoom
            controls += NavigationControl.AutoCentre
            controls += NavigationControl.Tilt
            controls += NavigationControl.AlertSignal
            controls += NavigationControl.AlertReroute
            controls += NavigationControl.AlertOffRoute
            controls += NavigationControl.AlertArrival
            controls += NavigationControl.AlertTunnel
            controls += NavigationControl.AlertCrossing
            controls += NavigationControl.AlertHazard
        }
    }
    return controls
}

/** The control an alert switch owns in a reachability test. */
fun alertControl(switch: NavigationAlertSwitch): NavigationControl =
    when (switch) {
        NavigationAlertSwitch.Signal -> NavigationControl.AlertSignal
        NavigationAlertSwitch.Reroute -> NavigationControl.AlertReroute
        NavigationAlertSwitch.OffRoute -> NavigationControl.AlertOffRoute
        NavigationAlertSwitch.Arrival -> NavigationControl.AlertArrival
        NavigationAlertSwitch.Tunnel -> NavigationControl.AlertTunnel
        NavigationAlertSwitch.Crossing -> NavigationControl.AlertCrossing
        NavigationAlertSwitch.Hazard -> NavigationControl.AlertHazard
    }

/** The string keys the navigation screen resolves. */
object NavigationScreenStrings {
    const val END = "nav_screen_end"
    const val PAUSE = "nav_screen_pause"
    const val RESUME = "nav_screen_resume"
    const val DONE = "nav_screen_done"
    const val EXPAND = "nav_screen_expand"
    const val COLLAPSE = "nav_screen_collapse"
    const val RECENTER = "nav_screen_recenter"
    const val CAMERA_AND_ALERTS = "nav_screen_camera_and_alerts"
    const val SETTINGS_SUBTITLE = "nav_screen_settings_subtitle"
    const val CAMERA = "nav_screen_camera"
    const val ALERTS_ALL_OFF = "nav_screen_alerts_all_off"
    const val ALERTS_ON = "nav_screen_alerts_on"
    const val AUTO_ZOOM = "nav_screen_auto_zoom"
    const val AUTO_ZOOM_DETAIL = "nav_screen_auto_zoom_detail"
    const val AUTO_CENTRE = "nav_screen_auto_centre"
    const val AUTO_CENTRE_DETAIL = "nav_screen_auto_centre_detail"
    const val THREE_D = "nav_screen_3d"
    const val THREE_D_DETAIL = "nav_screen_3d_detail"
    const val RESET = "nav_screen_reset"
    const val BACK = "nav_screen_back"
    const val SPEED_CURRENT = "nav_screen_speed_current"
    const val SPEED_AVERAGE = "nav_screen_speed_average"
    const val SPEED_MAX = "nav_screen_speed_max"
    const val SPEED_UNIT = "nav_screen_speed_unit"
    const val TRIP_LEFT = "nav_screen_trip_left"
    const val TRIP_LEFT_NO_ARRIVAL = "nav_screen_trip_left_no_arrival"
    const val NEXT_STOP = "nav_screen_next_stop"
    const val NEXT_STOP_NAME = "nav_screen_next_stop_name"
    const val DESTINATION = "nav_screen_destination"
    const val OFF_ROUTE = "nav_screen_off_route"
    const val FINDING_ROUTE = "nav_screen_finding_route"
    const val KEEP_GOING = "nav_screen_keep_going"
    const val PAUSED = "nav_screen_paused"
    const val PAUSED_DETAIL = "nav_screen_paused_detail"
    const val PAUSED_STRIP = "nav_screen_paused_strip"
    const val ARRIVED = "nav_screen_arrived"
    const val LAST_TURN = "nav_screen_last_turn"
    const val ALERT_IN = "nav_screen_alert_in"
    const val ALERT_NO_FIX = "nav_screen_alert_no_fix"
    const val ALERT_FROM_HERE = "nav_screen_alert_from_here"
    const val ALERT_CROSSING = "nav_screen_alert_crossing"
    const val ALERT_TUNNEL = "nav_screen_alert_tunnel"
    const val ALERT_FORD = "nav_screen_alert_ford"
    const val ALERT_BARRIER = "nav_screen_alert_barrier"
    const val ALERT_HAZARD = "nav_screen_alert_hazard"
    const val ALERT_GPS_LOST = "nav_screen_alert_gps_lost"
    const val ALERT_GPS_BACK = "nav_screen_alert_gps_back"
    const val ALERT_RECALCULATED = "nav_screen_alert_recalculated"
    const val ALERT_ARRIVED = "nav_screen_alert_arrived"
    const val ALERT_FAVOURITE = "nav_screen_alert_favourite"
    const val ALERT_WAYPOINT = "nav_screen_alert_waypoint"
    const val ALERT_PLACE = "nav_screen_alert_place"
    const val ALERT_PLACE_DETAIL = "nav_screen_alert_place_detail"
    const val THIS_TRIP = "nav_screen_this_trip"
    const val CAMERA_SUMMARY_ALL_ON = "nav_screen_camera_summary_all_on"
    const val CAMERA_SUMMARY_CUSTOM = "nav_screen_camera_summary_custom"
    const val ALERTS_SUMMARY_OFF = "nav_screen_alerts_summary_off"
    const val ALERTS_SUMMARY_ON = "nav_screen_alerts_summary_on"
    const val ALERT_SIGNAL = "nav_screen_alert_signal"
    const val ALERT_REROUTE = "nav_screen_alert_reroute"
    const val ALERT_OFF_ROUTE = "nav_screen_alert_off_route"
    const val ALERT_ARRIVAL = "nav_screen_alert_arrival"
    const val SECONDS = "nav_screen_seconds"
}

/** The label of an alert switch, in the sheet's chip order. */
fun alertSwitchLabel(switch: NavigationAlertSwitch): String =
    when (switch) {
        NavigationAlertSwitch.Signal -> NavigationScreenStrings.ALERT_SIGNAL
        NavigationAlertSwitch.Reroute -> NavigationScreenStrings.ALERT_REROUTE
        NavigationAlertSwitch.OffRoute -> NavigationScreenStrings.ALERT_OFF_ROUTE
        NavigationAlertSwitch.Arrival -> NavigationScreenStrings.ALERT_ARRIVAL
        NavigationAlertSwitch.Tunnel -> NavigationScreenStrings.ALERT_TUNNEL
        NavigationAlertSwitch.Crossing -> NavigationScreenStrings.ALERT_CROSSING
        NavigationAlertSwitch.Hazard -> NavigationScreenStrings.ALERT_HAZARD
    }

/** Whether one switch is on in [state]. */
fun NavigationScreenState.alertSwitchEnabled(switch: NavigationAlertSwitch): Boolean =
    when (switch) {
        NavigationAlertSwitch.Signal -> alertSwitches.signal
        NavigationAlertSwitch.Reroute -> alertSwitches.reroute
        NavigationAlertSwitch.OffRoute -> alertSwitches.offRoute
        NavigationAlertSwitch.Arrival -> alertSwitches.arrival
        NavigationAlertSwitch.Tunnel -> alertSwitches.tunnel
        NavigationAlertSwitch.Crossing -> alertSwitches.crossing
        NavigationAlertSwitch.Hazard -> alertSwitches.hazard
    }
