package nl.r1a.grassmap.plugin.api.model

/**
 * The hiking network a route relation belongs to (T-0418).
 *
 * OSM's `network` tag uses `iwn`, `nwn`, `rwn` and `lwn` for hiking routes.
 * A relation without the tag, or with a value this layer does not know, is
 * drawn as a local route: the product style has no fifth band and dropping the
 * route would hide a real trail.
 */
enum class HikingNetwork(
    val tag: String,
) {
    International("iwn"),
    National("nwn"),
    Regional("rwn"),
    Local("lwn"),

    ;

    companion object {
        fun fromTag(tag: String?): HikingNetwork = entries.firstOrNull { it.tag == tag } ?: Local
    }
}

/** One way member of a route relation, in relation order. */
data class RouteRelationMember(
    val wayId: Long,
    /** OSM role, such as `forward` or `backward`; empty when the relation has none. */
    val role: String?,
)

/**
 * One OSM hiking route relation as the region component stores it.
 *
 * [members] is the relation's own member order. The member geometry is not
 * copied per relation: [RouteRelations.ways] holds it once, keyed by way id, so
 * two routes that share a path share its bytes. The plugin assembles the line.
 */
data class RouteRelation(
    val id: Long,
    val route: String,
    val network: HikingNetwork,
    val ref: String?,
    val name: String?,
    val osmcSymbol: String?,
    val colour: String?,
    val distanceMeters: Double?,
    val members: List<RouteRelationMember>,
) {
    init {
        require(id > 0L) { "a relation id must be positive" }
        require(route.isNotBlank()) { "a relation carries its route type" }
        require(members.isNotEmpty()) { "a route relation carries at least one way member" }
        require(members.all { it.wayId > 0L }) { "member way ids must be positive" }
        require(distanceMeters == null || (distanceMeters.isFinite() && distanceMeters >= 0.0)) {
            "the distance must be finite and not negative"
        }
    }
}

/**
 * The region's hiking route relations and the geometry of every member way.
 *
 * A way's points are WGS84 degrees in the way's own direction. The relation's
 * member order and the way direction together are what the geometry assembler
 * uses; nothing is pre-joined here.
 */
data class RouteRelations(
    val ways: Map<Long, List<GeoPoint>>,
    val relations: List<RouteRelation>,
) {
    init {
        require(ways.all { (wayId, points) -> wayId > 0L && points.size >= 2 }) {
            "every way carries an id and at least two points"
        }
        require(ways.values.flatten().all { it.latitude.isFinite() && it.longitude.isFinite() }) {
            "the way coordinates must be finite"
        }
        require(relations.map { it.id }.toSet().size == relations.size) {
            "relation ids are unique"
        }
        require(
            relations.all { relation ->
                relation.members.all { member -> ways.containsKey(member.wayId) }
            },
        ) {
            "every member way has geometry"
        }
    }

    companion object {
        val EMPTY: RouteRelations = RouteRelations(emptyMap(), emptyList())
    }
}
