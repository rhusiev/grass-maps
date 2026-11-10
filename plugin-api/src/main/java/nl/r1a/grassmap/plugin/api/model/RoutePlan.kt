package nl.r1a.grassmap.plugin.api.model

import kotlinx.serialization.Serializable

/**
 * The two ends of a planned route. A later task adds vias; the role keeps the
 * swap operation unambiguous until then.
 */
@Serializable
enum class RoutePlanRole {
    Start,
    Destination,
}

/** One end of a planned route, with the text the user picked it from. */
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
 * The whole planning state a route-planner plugin publishes (T-0302).
 *
 * [generation] increments on every successful route request, so a repeated
 * route with unchanged geometry still refits the camera. [selectedIndex] picks
 * one of [options]; the list is the engine's answer in its own order, and the
 * first option is the primary route.
 */
@Serializable
data class RoutePlanState(
    val start: RoutePlanPoint? = null,
    val destination: RoutePlanPoint? = null,
    val status: RoutePlanStatus = RoutePlanStatus.Idle,
    val options: List<RoutePlanOption> = emptyList(),
    val selectedIndex: Int = 0,
    val generation: Long = 0,
) {
    /** The selected route, or null before a request succeeds. */
    val selected: RoutePlanOption?
        get() = options.getOrNull(selectedIndex)

    /** The end the next map long press should fill, or null when not picking. */
    val pickingRole: RoutePlanRole?
        get() =
            when (status) {
                RoutePlanStatus.PickingStart -> RoutePlanRole.Start
                RoutePlanStatus.PickingDestination -> RoutePlanRole.Destination
                else -> null
            }
}
