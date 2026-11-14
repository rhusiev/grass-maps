package nl.r1a.grassmap.plugin.api.model

import kotlinx.serialization.Serializable

/**
 * The three things a proximity alert can announce (T-0413): a favourite, a
 * waypoint of the followed track, and a place of a kind the user picked.
 *
 * The kind names the category, not the place. Each category has its own switch
 * in the alert host, so turning one off leaves the other two alone.
 */
@Serializable
enum class ProximityPlaceKind {
    Favourite,
    TrackWaypoint,
    Poi,
}
