package nl.r1a.grassmap.plugin.api.model

/**
 * One OpenStreetMap feature a way alert can announce (T-0319).
 *
 * [Ford] and [Barrier] are separate kinds because the route-warning list
 * (T-0409) will name them; the way-alert switches group both under the hazard
 * switch, as the product scope does.
 */
enum class WayAlertFeatureKind {
    RailwayCrossing,
    Tunnel,
    Hazard,
    Ford,
    Barrier,
}

/** Which OpenStreetMap element the feature came from, for its identity. */
enum class WayAlertElementType {
    Node,
    Way,
}

/**
 * One region feature that can produce a way alert: a point for a node, a line
 * for a way. Coordinates are WGS84 degrees, as the region component stores
 * them.
 */
data class WayAlertFeature(
    val kind: WayAlertFeatureKind,
    val elementType: WayAlertElementType,
    val osmId: Long,
    val geometry: List<GeoPoint>,
) {
    init {
        require(geometry.isNotEmpty()) { "a feature carries at least one point" }
        require(elementType != WayAlertElementType.Node || geometry.size == 1) {
            "a node feature carries exactly one point"
        }
        require(elementType != WayAlertElementType.Way || geometry.size >= 2) {
            "a way feature carries at least two points"
        }
        require(geometry.all { it.latitude.isFinite() && it.longitude.isFinite() }) {
            "the feature coordinates must be finite"
        }
    }
}

/** One feature found inside the route's corridor, with its position on the route. */
data class WayAlertHit(
    val feature: WayAlertFeature,
    /** The earliest distance along the route where the feature is inside the corridor. */
    val alongRouteMeters: Double,
    /** The closest distance from the route line to the feature. */
    val distanceFromRouteMeters: Double,
) {
    init {
        require(alongRouteMeters.isFinite() && alongRouteMeters >= 0.0) {
            "the along-route distance must be finite and not negative"
        }
        require(distanceFromRouteMeters.isFinite() && distanceFromRouteMeters >= 0.0) {
            "the corridor distance must be finite and not negative"
        }
    }
}

/**
 * The identity of one announced feature inside one route revision. A feature
 * is announced once per revision, so a reroute re-arms it and a route that is
 * followed without a replacement does not repeat it.
 */
data class WayAlertKey(
    val routeRevision: Long,
    val elementType: WayAlertElementType,
    val osmId: Long,
)
