package nl.r1a.grassmap.plugin.api.model

import kotlinx.serialization.Serializable
import nl.r1a.grassmap.plugin.api.provider.RouteInstruction
import nl.r1a.grassmap.plugin.api.provider.RouteOptions
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteSegment

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
 * Why the last request failed. [NoRoute] is the engine's answer to a valid
 * request; the other values say the engine could not be reached at all, so the
 * shell can tell the user to install or repair the pack instead of blaming the
 * points they picked.
 */
@Serializable
enum class RoutePlanFailure {
    NoRoute,
    EngineUnavailable,
    PackMissing,
    PackUnusable,

    /**
     * The route's ends lie in oblasts that are not on the phone. The state's
     * [RoutePlanState.missing] says which regions and parts to download.
     */
    RegionsMissing,
}

/** One part of a region the route needs and the phone does not have. */
@Serializable
data class MissingRegionPart(
    val kind: CatalogPartKind,
    val bytes: Long,
)

/**
 * One oblast a route needs. [parts] are the parts of its package that are not
 * on the phone, with the catalogue's sizes; [bytes] is their sum.
 */
@Serializable
data class MissingRegion(
    val region: String,
    val nameEn: String? = null,
    val nameUk: String? = null,
    val parts: List<MissingRegionPart> = emptyList(),
) {
    val bytes: Long
        get() = parts.sumOf { it.bytes }

    /** The name in the user's language, falling back to the other one. */
    fun name(preferUkrainian: Boolean): String? = if (preferUkrainian) nameUk ?: nameEn else nameEn ?: nameUk
}

/** Everything a route needs that is not on the phone, in the order its stops need it. */
@Serializable
data class MissingRegions(
    val regions: List<MissingRegion>,
) {
    val bytes: Long
        get() = regions.sumOf { it.bytes }
}

/**
 * Where "Download and route" is. [bytesDone] and [bytesTotal] add up the
 * transfers of the missing regions; [problem] is set when one of them failed,
 * and the prompt then offers the download again.
 */
@Serializable
data class MissingRegionsDownload(
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val problem: DownloadProblem? = null,
) {
    val isRunning: Boolean
        get() = problem == null
}

/**
 * One returned route as the plan publishes it. The geometry is kept because
 * the map draws it and the shell must not derive a second copy.
 *
 * [segments] and [instructions] are the engine's own time model for that route:
 * one segment per leg between waypoints, and one instruction per maneuver. They
 * travel with the option so guidance counts down from the same numbers the
 * route was built with instead of recomputing them from the totals.
 *
 * [runs] are the annotate pass's per-edge attribute runs, one per graph edge,
 * and stay empty until that pass answers. They are a separate field from
 * [segments] so a reroute still reads one waypoint per leg after annotation.
 * The two lists use the range conventions `RouteSegment` documents.
 */
@Serializable
data class RoutePlanOption(
    val geometry: List<GeoPoint>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val arrivalEpochMillis: Long,
    val segments: List<RouteSegment> = emptyList(),
    val runs: List<RouteSegment> = emptyList(),
    val instructions: List<RouteInstruction> = emptyList(),
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
 *
 * [failure] is set only while [status] is [RoutePlanStatus.Failed], and says
 * whether the points or the engine were at fault.
 *
 * [undoAvailable] and [redoAvailable] drive the panel's two controls (T-0304).
 * They report the plan's own edit history, not the command bus's global undo:
 * one edit is one change to the waypoint chain, and the route a request
 * produced is part of that edit, so undo returns the line the user saw.
 *
 * [profilePreferences] are the profile defaults read for the last request and
 * [overridePreferences] is the one-route override, null while the route follows
 * the profile. [preferences] is what the request actually carried. Keeping the
 * two sources apart lets the preview mark each profile default and reset this
 * route without writing the profile (T-0421).
 *
 * [missing] is set with [RoutePlanFailure.RegionsMissing] and lists what to
 * download before the route can work (T-0511). [missingDownload] is set while
 * that download runs, or after it failed.
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
    val failure: RoutePlanFailure? = null,
    val undoAvailable: Boolean = false,
    val redoAvailable: Boolean = false,
    val profile: RouteProfile = RouteProfile.Walk,
    val profilePreferences: RouteOptions = RouteOptions(),
    val overridePreferences: RouteOptions? = null,
    val missing: MissingRegions? = null,
    val missingDownload: MissingRegionsDownload? = null,
) {
    /** The preferences the last route request carried. */
    val preferences: RouteOptions
        get() = overridePreferences ?: profilePreferences

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
