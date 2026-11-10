package nl.r1a.grassmap.plugins.valhalla

import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.RouteModifier
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest

/**
 * Builds the JSON body of a Valhalla `/route` request.
 *
 * Valhalla's overlays are directionless, so a directional modifier is applied
 * to both directions. The amount of added time becomes a multiplier using the
 * profile's nominal speed, because Valhalla has no per-edge added-seconds
 * overlay. The design (architecture section 15) names both translations as the
 * v1 mapping.
 */
internal object ValhallaRequest {
    private const val METRES_PER_DEGREE_LATITUDE = 111_320.0
    private const val EXCLUDE_BUFFER_METRES = 10.0
    private const val MIN_FACTOR = 0.1
    private const val MAX_FACTOR = 100_000.0

    /**
     * Builds one Valhalla request. With [useExclusionForModifiers], every
     * modifier becomes an exclusion ring instead of a linear cost factor. The
     * engine retries with that form when Valhalla cannot match a cost line to
     * graph edges, which is the normal result for the off-graph synthetic
     * geometry T-0024 uses.
     */
    fun build(
        request: RouteRequest,
        useExclusionForModifiers: Boolean = false,
    ): String {
        val profile = ValhallaProfiles.of(request.profile)
        return buildJsonObject {
            put("costing", profile.costing)
            putJsonObject("costing_options") {
                put(profile.costing, profile.costingOptions)
            }
            put("units", "kilometers")
            put("language", "uk-UA")
            putJsonArray("locations") {
                for (point in request.waypoints) {
                    addJsonObject {
                        put("lat", point.latitude)
                        put("lon", point.longitude)
                        put("type", "break")
                    }
                }
            }
            if (request.alternatives > 0) {
                put("alternates", request.alternatives)
            }

            val lineFactors =
                if (useExclusionForModifiers) {
                    emptyList()
                } else {
                    request.modifiers.mapNotNull { modifier ->
                        val factor = factorOf(modifier, request.profile)
                        if (factor == null || factor == 1.0) null else modifier.geometry to factor
                    }
                }
            if (lineFactors.isNotEmpty()) {
                putJsonArray("linear_cost_factors") {
                    for ((geometry, factor) in lineFactors) {
                        addJsonObject {
                            put("shape", Polyline6.encode(geometry))
                            put("factor", factor)
                        }
                    }
                }
            }

            val excluded =
                if (useExclusionForModifiers) {
                    request.modifiers
                } else {
                    request.modifiers.filter { it.forbidden }
                }
            if (excluded.isNotEmpty()) {
                putJsonArray("exclude_polygons") {
                    for (modifier in excluded) {
                        addJsonArray {
                            for (point in exclusionRing(modifier.geometry)) {
                                addJsonArray {
                                    add(point.longitude)
                                    add(point.latitude)
                                }
                            }
                        }
                    }
                }
            }
        }.toString()
    }

    /**
     * The multiplier Valhalla should apply to [modifier]. A forbidden modifier
     * is handled through `exclude_polygons` and has no factor.
     */
    fun factorOf(
        modifier: RouteModifier,
        profile: RouteProfile,
    ): Double? {
        modifier.multiplier?.let { return it.coerceIn(MIN_FACTOR, MAX_FACTOR) }
        val added = modifier.addedSeconds ?: return null
        if (modifier.forbidden) return null
        val seconds = lineLengthMetres(modifier.geometry) / profile.nominalSpeed
        return (1.0 + added / seconds.coerceAtLeast(1.0)).coerceIn(MIN_FACTOR, MAX_FACTOR)
    }

    /** The distance in metres along [points]. */
    fun lineLengthMetres(points: List<GeoPoint>): Double {
        var total = 0.0
        for (index in 1 until points.size) {
            total += distanceMetres(points[index - 1], points[index])
        }
        return total
    }

    /**
     * A closed rectangle around [points] with [EXCLUDE_BUFFER_METRES] on every
     * side. A rectangle is robust for the short synthetic lines v1 receives;
     * a per-segment corridor can self-intersect on a sharp turn.
     */
    fun exclusionRing(points: List<GeoPoint>): List<GeoPoint> {
        val latitude = points.firstOrNull()?.latitude ?: return emptyList()
        val latitudeDelta = EXCLUDE_BUFFER_METRES / METRES_PER_DEGREE_LATITUDE
        val longitudeDelta =
            EXCLUDE_BUFFER_METRES /
                (METRES_PER_DEGREE_LATITUDE * kotlin.math.cos(Math.toRadians(latitude)).coerceAtLeast(0.01))
        val south = points.minOf { it.latitude } - latitudeDelta
        val north = points.maxOf { it.latitude } + latitudeDelta
        val west = points.minOf { it.longitude } - longitudeDelta
        val east = points.maxOf { it.longitude } + longitudeDelta
        return listOf(
            GeoPoint(south, west),
            GeoPoint(south, east),
            GeoPoint(north, east),
            GeoPoint(north, west),
            GeoPoint(south, west),
        )
    }

    fun distanceMetres(
        from: GeoPoint,
        to: GeoPoint,
    ): Double {
        val latitudeDelta = Math.toRadians(to.latitude - from.latitude)
        val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
        val meanLatitude = Math.toRadians((from.latitude + to.latitude) / 2.0)
        val east = longitudeDelta * kotlin.math.cos(meanLatitude)
        return kotlin.math.hypot(latitudeDelta, east) * EARTH_RADIUS_METRES
    }

    private val RouteProfile.nominalSpeed: Double
        get() =
            when (this) {
                RouteProfile.Walk, RouteProfile.Hike -> 1.4
                RouteProfile.Bike -> 4.2
                RouteProfile.Car -> 13.9
            }

    private const val EARTH_RADIUS_METRES = 6_371_000.0
}
