package nl.r1a.grassmap.app

import nl.r1a.grassmap.alerts.BeepHost
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugins.proximity.alerts.ProximityAlertHost
import nl.r1a.grassmap.plugins.way.alerts.WayAlertHost

/**
 * The one app-level path that starts guidance (T-0413).
 *
 * A session is only audible when the tone source and both alert hosts are
 * prepared for it. [BeepHost] holds the profile's beep distance, [WayAlertHost]
 * holds the profile's warning distances plus a route cache and the set of
 * announced features, and [ProximityAlertHost] holds a place cache and the
 * approach latches. Before this class existed only the debug `core.nav.start`
 * command prepared them, so a trip started from the planner or from a followed
 * track left every alert inert however its switches were set. The planner's
 * Start, the follow-track Start and the command all call [start] now, so a new
 * caller cannot forget the preparation.
 *
 * The profile is the caller's choice: the route planner and the follow-track
 * sheet both use Walk today, and this class does not pick one.
 */
internal class GuidanceStarter(
    private val navigation: NavigationController,
    private val beeps: () -> BeepHost,
    private val wayAlerts: () -> WayAlertHost,
    private val proximityAlerts: () -> ProximityAlertHost,
) {
    /**
     * Prepares the hosts for [profile] and starts a session on [route].
     *
     * A start that fails starts no session. The controller refuses a route
     * shorter than two points, a missing location permission, or a foreground
     * service the system rejects. The hosts were already pointed at [profile]
     * and reset when the refusal arrives, so a refused start leaves them
     * explicitly reset, not in their previous state: no session runs, so there
     * are no latches that reset could clear, and the next successful start
     * prepares them again. The alternative - restoring the previous state -
     * would need each host's private schedule state and would still leave an
     * old profile's distances in place for the next attempt.
     *
     * A start while a session runs is not a failure. The controller reports
     * `started=false`, and this class does not touch the hosts at all, so a
     * running session keeps its profile, route cache and latches.
     */
    suspend fun start(
        route: Route,
        profile: RouteProfile,
        reroute: Boolean = true,
    ): Outcome<Boolean> {
        if (navigation.isActive) {
            return navigation.start(route, profile, reroute)
        }
        beeps().setProfile(profile)
        wayAlerts().setProfile(profile)
        wayAlerts().reset()
        proximityAlerts().reset()
        return navigation.start(route, profile, reroute)
    }
}
