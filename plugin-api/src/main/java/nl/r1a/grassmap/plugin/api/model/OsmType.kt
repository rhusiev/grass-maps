package nl.r1a.grassmap.plugin.api.model

/**
 * The canonical OpenStreetMap element type behind a place.
 *
 * A closed way that OSM treats as an area keeps [WAY]; the doubled area id is
 * an extraction detail, not a fourth type.
 */
enum class OsmType {
    NODE,
    WAY,
    RELATION,
}
