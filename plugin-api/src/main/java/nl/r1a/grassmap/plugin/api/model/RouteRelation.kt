package nl.r1a.grassmap.plugin.api.model

/**
 * The kind of route a relation carries (T-0418, T-0419).
 *
 * The component keeps hiking and cycling routes together: both are drawn as a
 * translucent band, and both may share a way. The design gives them separate
 * palettes and zoom levels, so the route kind is part of the model.
 */
enum class Route(
    val tag: String,
) {
    Hiking("hiking"),
    Cycling("bicycle"),
}

/** The four network tiers the style has a band for. */
enum class NetworkLevel {
    International,
    National,
    Regional,
    Local,
}

/**
 * The route network a relation belongs to, with its own OSM tag (T-0418).
 *
 * OSM's `network` tag uses `iwn`, `nwn`, `rwn` and `lwn` for hiking routes and
 * `icn`, `ncn`, `rcn` and `lcn` for cycling routes. A relation without the tag,
 * or with a value this layer does not know, is drawn as a local route: the
 * product style has no fifth band, and dropping the route would hide a real
 * trail.
 */
enum class RouteNetwork(
    val tag: String,
    val route: Route,
    val level: NetworkLevel,
) {
    HikingInternational("iwn", Route.Hiking, NetworkLevel.International),
    HikingNational("nwn", Route.Hiking, NetworkLevel.National),
    HikingRegional("rwn", Route.Hiking, NetworkLevel.Regional),
    HikingLocal("lwn", Route.Hiking, NetworkLevel.Local),
    CyclingInternational("icn", Route.Cycling, NetworkLevel.International),
    CyclingNational("ncn", Route.Cycling, NetworkLevel.National),
    CyclingRegional("rcn", Route.Cycling, NetworkLevel.Regional),
    CyclingLocal("lcn", Route.Cycling, NetworkLevel.Local),

    ;

    companion object {
        fun fromTag(
            route: Route,
            tag: String?,
        ): RouteNetwork = entries.firstOrNull { it.route == route && it.tag == tag } ?: local(route)

        fun local(route: Route): RouteNetwork =
            when (route) {
                Route.Hiking -> HikingLocal
                Route.Cycling -> CyclingLocal
            }
    }
}

/** One way member of a route relation, in relation order. */
data class RouteRelationMember(
    val wayId: Long,
    /** OSM role, such as `forward` or `backward`; empty when the relation has none. */
    val role: String?,
)

/**
 * One numbered junction of a cycling node network (T-0419).
 *
 * OSM tags the junction node itself (`lcn_ref`, `rcn_ref`, `ncn_ref` or
 * `icn_ref`), not the relation, and the network's route relations connect
 * these nodes. [reference] is the number a rider follows, kept as text because
 * OSM allows a letter suffix.
 */
data class RouteNetworkNode(
    val id: Long,
    val point: GeoPoint,
    val network: RouteNetwork,
    val reference: String,
) {
    init {
        require(id > 0L) { "a node id must be positive" }
        require(network.route == Route.Cycling) { "node-network numbers are cycling routes" }
        require(reference.isNotBlank()) { "a node-network junction carries its number" }
        require(point.latitude.isFinite() && point.longitude.isFinite()) {
            "the node coordinates must be finite"
        }
    }
}

/**
 * One OSM route relation as the region component stores it.
 *
 * [members] is the relation's own member order. The member geometry is not
 * copied per relation: [RouteRelations.ways] holds it once, keyed by way id, so
 * two routes that share a path share its bytes. The plugin assembles the line.
 */
data class RouteRelation(
    val id: Long,
    val route: Route,
    val network: RouteNetwork,
    val ref: String?,
    val name: String?,
    val osmcSymbol: String?,
    val colour: String?,
    val distanceMeters: Double?,
    val members: List<RouteRelationMember>,
) {
    init {
        require(id > 0L) { "a relation id must be positive" }
        require(network.route == route) { "a relation's network belongs to its route kind" }
        require(members.isNotEmpty()) { "a route relation carries at least one way member" }
        require(members.all { it.wayId > 0L }) { "member way ids must be positive" }
        require(distanceMeters == null || (distanceMeters.isFinite() && distanceMeters >= 0.0)) {
            "the distance must be finite and not negative"
        }
    }
}

/**
 * The region's hiking and cycling route relations, the geometry of every
 * member way, and the numbered junctions of the cycling node networks.
 *
 * A way's points are WGS84 degrees in the way's own direction. The relation's
 * member order and the way direction together are what the geometry assembler
 * uses; nothing is pre-joined here. [nodes] is independent of the relations: a
 * node-network junction is a tagged OSM node, not a relation member.
 */
data class RouteRelations(
    val ways: Map<Long, List<GeoPoint>>,
    val relations: List<RouteRelation>,
    val nodes: List<RouteNetworkNode> = emptyList(),
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
        require(nodes.map { it.id }.toSet().size == nodes.size) {
            "node ids are unique"
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
