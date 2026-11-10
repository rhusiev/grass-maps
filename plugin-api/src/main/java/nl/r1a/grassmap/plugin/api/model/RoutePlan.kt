package nl.r1a.grassmap.plugin.api.model

import kotlinx.serialization.Serializable

/**
 * The two ends of a planned route. The role keeps the swap operation
 * unambiguous; a via is an intermediate stop and is addressed by its index in
 * [RoutePlanState.vias] instead.
 */
@Serializable
enum class RoutePlanRole {
    Start,
    Destination,
}

/** One end or stop of a planned route, with the text the user picked it from. */
@Serializable
data class RoutePlanPoint(
    val position: GeoPoint,
    val label: String = "",
)

/**
 * Where the planner is. `Picking*` states drive the map's next long press;
 * [RoutePlanStatus.Ready] means [RoutePlanState.options] holds a route list.
 */
@Serializable
enum class RoutePlanStatus {
    Idle,
    PickingStart,
    PickingDestination,
    Planning,
    Ready,
    Failed,
}

/**
 * One returned route as the plan publishes it. The geometry is kept because
 * the map draws it and the shell must not derive a second copy.
 */
@Serializable
data class RoutePlanOption(
    val geometry: List<GeoPoint>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val arrivalEpochMillis: Long,
)

/**
 * The whole planning state a route-planner plugin publishes (T-0302, vias in
 * T-0303).
 *
 * [generation] increments on every successful route request, so a repeated
 * route with unchanged geometry still refits the camera. [selectedIndex] picks
 * one of [options]; the list is the engine's answer in its own order, and the
 * first option is the primary route.
 *
 * [vias] are the intermediate stops in visit order, between [start] and
 * [destination]. One route request carries all of them as one ordered waypoint
 * chain, so the engine returns one route with one leg per hop; the planner never
 * joins independent routes.
 */
@Serializable
data class RoutePlanState(
    val start: RoutePlanPoint? = null,
    val destination: RoutePlanPoint? = null,
    val status: RoutePlanStatus = RoutePlanStatus.Idle,
    val options: List<RoutePlanOption> = emptyList(),
    val selectedIndex: Int = 0,
    val generation: Long = 0,
    val vias: List<RoutePlanPoint> = emptyList(),
) {
    /** The selected route, or null before a request succeeds. */
    val selected: RoutePlanOption?
        get() = options.getOrNull(selectedIndex)

    /**
     * Start, every via in visit order and the destination, skipping an end that
     * is not set yet. This is the order the engine is asked to route through.
     */
    val stops: List<RoutePlanPoint>
        get() =
            buildList {
                start?.let(::add)
                addAll(vias)
                destination?.let(::add)
            }

    /** The end the next map long press should fill, or null when not picking. */
    val pickingRole: RoutePlanRole?
        get() =
            when (status) {
                RoutePlanStatus.PickingStart -> RoutePlanRole.Start
                RoutePlanStatus.PickingDestination -> RoutePlanRole.Destination
                else -> null
            }
}
