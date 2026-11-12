package nl.r1a.grassmap.app

import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.navigation.NavigationState
import nl.r1a.grassmap.plugins.way.alerts.WayAlertPhase
import nl.r1a.grassmap.plugins.way.alerts.WayAlertState

/**
 * Maps the published navigation state onto the way-alert schedule's own input
 * (T-0319). The plugin module does not import `navigation`: the app owns the
 * phase mapping and the travelled distance, and the schedule owns the policy.
 */
internal fun NavigationState.toWayAlertState(): WayAlertState {
    val route = activeRoute
    return WayAlertState(
        phase =
            when (phase) {
                NavigationPhase.Idle -> WayAlertPhase.Idle
                NavigationPhase.Following -> WayAlertPhase.Following
                NavigationPhase.Rerouting -> WayAlertPhase.Rerouting
                NavigationPhase.Paused -> WayAlertPhase.Paused
                NavigationPhase.Arrived -> WayAlertPhase.Arrived
                NavigationPhase.Stopped -> WayAlertPhase.Stopped
            },
        routeRevision = routeRevision,
        route = route,
        alongRouteMeters = if (route != null) route.distanceMeters - remainingDistanceMeters else 0.0,
        fixTimeMillis = fix?.timeMillis,
    )
}
