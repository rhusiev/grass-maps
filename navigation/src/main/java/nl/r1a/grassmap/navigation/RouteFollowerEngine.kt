package nl.r1a.grassmap.navigation

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.provider.Route

/**
 * The route-following core behind [NavigationSession]. Implementations convert
 * our route into their own model and return an engine-neutral snapshot for
 * every fix; no engine type crosses this boundary.
 */
interface RouteFollowerEngine {
    /** Starts following [route]. A failure leaves the session on its geometry fallback. */
    fun begin(route: Route): Outcome<RouteFollowerHandle>
}

/** One active follower for one route. */
interface RouteFollowerHandle {
    /** The state for the first fix, which also starts the trip clock. */
    fun initialFix(fix: LocationFix): Outcome<FollowerSnapshot>

    /** The state after one more fix. */
    fun update(fix: LocationFix): Outcome<FollowerSnapshot>

    /** Releases native resources. It never throws. */
    fun close()
}

/** One follower reading, already translated into our vocabulary. */
data class FollowerSnapshot(
    val snappedPosition: GeoPoint?,
    val deviation: FollowerDeviation = FollowerDeviation.None,
    val deviationMeters: Double? = null,
    val arrived: Boolean = false,
    val remainingDistanceMeters: Double? = null,
    val remainingDurationSeconds: Double? = null,
    val distanceToNextInstructionMeters: Double? = null,
    val nextInstruction: String? = null,
)

/** The deviation verdicts the core exposes. */
enum class FollowerDeviation {
    None,
    OffStepOnRoute,
    OffRoute,
    Unknown,
}
