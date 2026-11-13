package nl.r1a.grassmap.plugin.api.model

import kotlinx.serialization.Serializable
import nl.r1a.grassmap.plugin.api.provider.RouteOptions

/**
 * One route the planner produced, kept for reuse (T-0304).
 *
 * The entry stores the waypoint chain and the totals of the route that was
 * selected, not the geometry: reusing an entry asks the engine for a fresh
 * route along the same chain, because a stored line would bypass one-way rules
 * the engine re-evaluates and would age without the map data.
 *
 * [atMillis] is the injected clock's time when the route was produced; it is
 * the ordering key. [label]s travel inside [start], [vias] and [destination],
 * so a reused plan keeps the text the points were picked from.
 *
 * [preferences] are the route preferences the entry was produced with
 * (T-0421), so a reuse restores what the user chose rather than the current
 * profile defaults. An entry written before this field decodes as all
 * [nl.r1a.grassmap.plugin.api.provider.RoutePreference.Normal].
 */
@Serializable
data class RouteHistoryEntry(
    val start: RoutePlanPoint,
    val destination: RoutePlanPoint,
    val vias: List<RoutePlanPoint> = emptyList(),
    val distanceMeters: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val atMillis: Long = 0,
    val preferences: RouteOptions = RouteOptions(),
) {
    /** The whole chain in visit order, which is what a reuse sets. */
    val stops: List<RoutePlanPoint>
        get() =
            buildList {
                add(start)
                addAll(vias)
                add(destination)
            }
}

/** The route entries a planner keeps as its reuse history. */
object RouteHistoryRules {
    /** The number of routes the history keeps, newest first. */
    const val DEFAULT_MAX_ENTRIES: Int = 20

    /**
     * Two entries name the same route when their chains carry the same points
     * in the same order; labels are the text the points were picked from, so
     * they take part in the identity too.
     */
    fun sameChain(
        left: RouteHistoryEntry,
        right: RouteHistoryEntry,
    ): Boolean = left.stops == right.stops
}
