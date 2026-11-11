package nl.r1a.grassmap.navigation

import nl.r1a.grassmap.plugin.api.provider.RouteProfile

/**
 * The seam's tuning.
 *
 * [maxDeviationMeters] feeds both Ferrostar's `StaticThreshold` deviation
 * detector and the geometry fallback, so the two agree.
 *
 * [offRouteDelaySeconds] is the reroute budget in time, and the gate converts
 * it to distance with the profile's own speed, so 20 s of travel is about 22 m
 * walking and 56 m cycling. The gate is distance, not wall clock: a fix that
 * stands still never spends the budget, and a single jump in the fix never
 * spends it either. [minimumHorizontalAccuracyMeters] is the same value
 * Ferrostar uses to refuse deviation evidence; a fix worse than it neither
 * starts nor cancels a reroute. [offRouteConfirmFixes] is the existing two-fix
 * confirmation, which still has to agree with the distance gate.
 *
 * [arrivalDistanceMeters] is the single arrival radius: it sets the core's
 * end-of-step advance and the session's own remaining-distance check, so the
 * core cannot finish the trip while the session still counts.
 */
data class NavigationConfig(
    val maxDeviationMeters: Double = 35.0,
    val minimumHorizontalAccuracyMeters: Int = 15,
    val offRouteConfirmFixes: Int = 2,
    val offRouteDelaySeconds: Double = 20.0,
    val profileSpeedsMetersPerSecond: Map<RouteProfile, Double> = PROFILE_SPEEDS_METERS_PER_SECOND,
    val arrivalDistanceMeters: Double = 15.0,
    val waypointAdvanceMeters: Double = 100.0,
    val reroute: Boolean = true,
) {
    init {
        require(maxDeviationMeters > 0.0)
        require(minimumHorizontalAccuracyMeters > 0)
        require(offRouteConfirmFixes >= 1)
        require(offRouteDelaySeconds > 0.0)
        require(
            RouteProfile.entries.all { profile ->
                val speed = profileSpeedsMetersPerSecond[profile]
                speed != null && speed.isFinite() && speed > 0.0
            },
        )
        require(arrivalDistanceMeters >= 0.0)
        require(waypointAdvanceMeters > 0.0)
    }

    /** The distance the gate spends after [offRouteDelaySeconds] of travel on [profile]. */
    fun travelMeters(profile: RouteProfile): Double = offRouteDelaySeconds * speedMetersPerSecond(profile)

    /** The profile's nominal moving speed; the gate's only profile input. */
    fun speedMetersPerSecond(profile: RouteProfile): Double = profileSpeedsMetersPerSecond.getValue(profile)

    companion object {
        /**
         * The product criterion is OsmAnd's 20 s of travel: about 22 m walking
         * and 56 m cycling (product-scope.md, "Routing and navigation"). The
         * gate speeds below are those two distances over 20 s, 1.1 m/s and
         * 2.8 m/s. They are deliberately not Valhalla's flat costing speeds
         * (5 and 18 km/h): the gate asks how far a person has kept moving after
         * leaving the line, and at that question the acceptance names a walking
         * pace with stops and a city-bike pace.
         */
        val PROFILE_SPEEDS_METERS_PER_SECOND: Map<RouteProfile, Double> =
            mapOf(
                RouteProfile.Walk to 1.1,
                RouteProfile.Hike to 1.1,
                RouteProfile.Bike to 2.8,
                RouteProfile.Car to 13.9,
            )
    }
}
