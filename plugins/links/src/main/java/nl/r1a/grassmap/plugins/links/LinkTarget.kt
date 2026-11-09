package nl.r1a.grassmap.plugins.links

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.OsmType

/**
 * Where an external link sends the map (T-0211).
 *
 * The two shapes are the ones a link can honestly produce. [Point] carries a
 * position, so the map can centre on it. [Element] names an OSM object; it
 * carries a position only when the URL also has osm.org's `#map=` fragment. A
 * bare element URL has no coordinates, and this app has no offline element
 * lookup, so the router does not invent one.
 */
sealed interface LinkTarget {
    /** A position to centre, with the zoom the link asked for, if it named one. */
    data class Point(
        val point: GeoPoint,
        val zoom: Double?,
        val label: String? = null,
    ) : LinkTarget

    /** An OSM object, with the `#map=` position when the URL carried one. */
    data class Element(
        val type: OsmType,
        val id: Long,
        val point: GeoPoint? = null,
        val zoom: Double? = null,
    ) : LinkTarget
}

/** Why a link did not become a [LinkTarget]. */
enum class LinkFailure {
    /** A link of a known kind whose contents do not parse. */
    MALFORMED,

    /** A well-formed link of a kind this app does not open. */
    UNSUPPORTED,

    /** A position outside the range a geographic coordinate can have. */
    OUT_OF_RANGE,
}

/** The routing decision for one link: a target or a typed failure. */
sealed interface LinkResult {
    data class Success(
        val target: LinkTarget,
    ) : LinkResult

    data class Failure(
        val reason: LinkFailure,
    ) : LinkResult
}
