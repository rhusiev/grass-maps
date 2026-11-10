package nl.r1a.grassmap.navigation

/**
 * The seam's tuning. T-0025 uses one static threshold plus a confirmation
 * count; T-0312 replaces this with the 20 s of travel, profile-scaled and
 * debounced policy from architecture section 16.
 *
 * [maxDeviationMeters] feeds both Ferrostar's `StaticThreshold` deviation
 * detector and the geometry fallback, so the two agree.
 *
 * [arrivalDistanceMeters] is the single arrival radius: it sets the core's
 * end-of-step advance and the session's own remaining-distance check, so the
 * core cannot finish the trip while the session still counts.
 */
data class NavigationConfig(
    val maxDeviationMeters: Double = 35.0,
    val minimumHorizontalAccuracyMeters: Int = 15,
    val offRouteConfirmFixes: Int = 2,
    val rerouteCooldownFixes: Int = 10,
    val arrivalDistanceMeters: Double = 15.0,
    val waypointAdvanceMeters: Double = 100.0,
    val reroute: Boolean = true,
)
