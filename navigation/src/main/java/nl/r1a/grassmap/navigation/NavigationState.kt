package nl.r1a.grassmap.navigation

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.provider.Route

/** Where the session is in its own lifecycle. Off-route is a relation, not a phase. */
enum class NavigationPhase {
    Idle,
    Following,
    Rerouting,
    Arrived,
    Stopped,
}

/** How the current fix relates to the active route line. */
enum class RouteRelation {
    Unknown,
    OnRoute,

    /** Off the current step line but still on the route. */
    OffStepOnRoute,

    /** Beyond the configured deviation threshold. */
    OffRoute,
}

/** One guidance instruction ahead of the driver, in engine-neutral terms. */
data class NavigationInstruction(
    val text: String,
    val streetName: String? = null,
    val distanceToInstructionMeters: Double? = null,
)

/**
 * One emitted navigation state. It carries the active route so a consumer can
 * draw a rerouted line without a second channel; [routeRevision] changes only
 * when the route is replaced.
 */
data class NavigationState(
    val phase: NavigationPhase = NavigationPhase.Idle,
    val fix: LocationFix? = null,
    val snappedPosition: GeoPoint? = null,
    val relation: RouteRelation = RouteRelation.Unknown,
    val distanceFromRouteMeters: Double = 0.0,
    val remainingDistanceMeters: Double = 0.0,
    val remainingDurationSeconds: Double = 0.0,
    val nextInstruction: NavigationInstruction? = null,
    val activeRoute: Route? = null,
    val routeRevision: Long = 0,
    val rerouteCount: Int = 0,
    val lastRerouteMillis: Long? = null,
)

/** Evidence from one completed replay or live session. */
data class NavigationRun(
    val fixCount: Int,
    val offRouteFixCount: Int,
    val rerouteCount: Int,
    val arrived: Boolean,
    val detectionMillis: Long?,
    val lastRerouteMillis: Long?,
    val finalRoute: Route?,
)
