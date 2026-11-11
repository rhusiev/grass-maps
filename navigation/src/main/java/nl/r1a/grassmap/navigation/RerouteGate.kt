package nl.r1a.grassmap.navigation

import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.provider.RouteProfile

/**
 * The 20 s of travel behind a reroute, as a distance budget.
 *
 * [NavigationConfig.offRouteDelaySeconds] is converted with the profile's own
 * gate speed, so walk spends 22 m and bike 56 m. The budget is spent by
 * movement, not by the clock: a fix that has not moved credits nothing, and
 * standing still off route therefore never fires. The same rule makes the
 * replay test honest - its timestamps advance with distance, so 20 s of replay
 * travel is 20 s of budget.
 *
 * One fix is never allowed to spend more than [MAX_PLAUSIBLE_STEP_FACTOR] times
 * the gate speed for its own elapsed time. A fix that jumps - 40 m between two
 * one-second fixes - is a new reading, not travel, and does not satisfy the
 * gate on its own. When the source reports a speed, a speed below
 * [MIN_MOVING_SPEED_METERS_PER_SECOND] also credits nothing, so a stationary
 * fix with a few metres of jitter does not walk the driver off route.
 */
internal class RerouteGate(
    profile: RouteProfile,
    config: NavigationConfig = NavigationConfig(),
) {
    private val gateSpeedMetersPerSecond = config.speedMetersPerSecond(profile)

    private val thresholdMeters: Double = config.travelMeters(profile)

    var travelledMeters: Double = 0.0
        private set

    /** True when some travel is banked but the budget is not spent yet. */
    val hasPending: Boolean get() = travelledMeters > 0.0

    /** True when the budget is spent and a confirmed off-route fix may reroute. */
    val isMet: Boolean get() = travelledMeters >= thresholdMeters

    /**
     * Adds the travel from [previous] to [fix] and reports whether the budget
     * is now spent. The caller only offers off-route fixes; an on-route fix
     * calls [reset], which is the "returned in time" cancel.
     */
    fun observe(
        previous: LocationFix?,
        fix: LocationFix,
    ) {
        if (previous != null) {
            travelledMeters += travelBetween(previous, fix)
        }
    }

    fun reset() {
        travelledMeters = 0.0
    }

    private fun travelBetween(
        previous: LocationFix,
        fix: LocationFix,
    ): Double {
        val elapsedMillis = fix.timeMillis - previous.timeMillis
        if (elapsedMillis <= 0L) {
            return 0.0
        }
        val step = GeoMath.distanceMeters(previous.position, fix.position)
        if (!step.isFinite() || step <= MIN_STEP_METERS) {
            return 0.0
        }
        val reportedSpeed = fix.speedMetersPerSecond
        if (reportedSpeed != null && reportedSpeed.isFinite() && reportedSpeed < MIN_MOVING_SPEED_METERS_PER_SECOND) {
            return 0.0
        }
        val impliedSpeed = step / (elapsedMillis / MILLIS_PER_SECOND)
        if (impliedSpeed > gateSpeedMetersPerSecond * MAX_PLAUSIBLE_STEP_FACTOR) {
            return 0.0
        }
        return step
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0
        const val MIN_STEP_METERS = 0.1
        const val MIN_MOVING_SPEED_METERS_PER_SECOND = 0.3
        const val MAX_PLAUSIBLE_STEP_FACTOR = 4.0
    }
}
