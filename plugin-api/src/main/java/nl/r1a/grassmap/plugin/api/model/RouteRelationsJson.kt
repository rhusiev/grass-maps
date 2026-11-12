package nl.r1a.grassmap.plugin.api.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * Decodes the region's `relations.json` component (T-0418).
 *
 * The component is written by `tools/pipeline/osm-extract --route-relations`
 * and read by both the region reader and the map plugin, so the schema has one
 * decoder. Any malformed member, missing way, or unknown format version is
 * [AppError.CorruptData]; a caller never receives a partially built value.
 */
object RouteRelationsJson {
    const val FORMAT: String = "grass-maps-route-relations"
    const val VERSION: Long = 1
    const val ROUTE_HIKING: String = "hiking"

    fun decode(text: String): Outcome<RouteRelations> {
        val root =
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (exception: IllegalArgumentException) {
                null
            } ?: return corrupt()
        if (root.string("format") != FORMAT || root.integer("version") != VERSION) {
            return corrupt()
        }
        val wayElements = root["ways"] as? JsonArray ?: return corrupt()
        val relationElements = root["relations"] as? JsonArray ?: return corrupt()
        val ways = LinkedHashMap<Long, List<GeoPoint>>()
        for (element in wayElements) {
            val way = element as? JsonObject ?: return corrupt()
            val id = way.integer("id") ?: return corrupt()
            if (id <= 0L || ways.containsKey(id)) {
                return corrupt()
            }
            val coordinates = way["coordinates"] as? JsonArray ?: return corrupt()
            if (coordinates.size < MIN_WAY_POINTS) {
                return corrupt()
            }
            ways[id] = coordinates.map { parsePoint(it) ?: return corrupt() }
        }
        val relations = mutableListOf<RouteRelation>()
        val ids = HashSet<Long>()
        for (element in relationElements) {
            val relation = element as? JsonObject ?: return corrupt()
            val id = relation.integer("id") ?: return corrupt()
            if (id <= 0L || !ids.add(id)) {
                return corrupt()
            }
            if (relation.string("type") != "route" || relation.string("route") != ROUTE_HIKING) {
                return corrupt()
            }
            val memberElements = relation["members"] as? JsonArray ?: return corrupt()
            if (memberElements.isEmpty()) {
                return corrupt()
            }
            val members =
                memberElements.map { memberElement ->
                    val member = memberElement as? JsonObject ?: return corrupt()
                    val wayId = member.integer("way") ?: return corrupt()
                    if (wayId <= 0L || !ways.containsKey(wayId)) {
                        return corrupt()
                    }
                    RouteRelationMember(wayId = wayId, role = member.string("role"))
                }
            val distance = relation.number("distanceMeters")
            if (distance != null && (!distance.isFinite() || distance < 0.0)) {
                return corrupt()
            }
            relations +=
                try {
                    RouteRelation(
                        id = id,
                        route = ROUTE_HIKING,
                        network = HikingNetwork.fromTag(relation.string("network")),
                        ref = relation.string("ref"),
                        name = relation.string("name"),
                        osmcSymbol = relation.string("osmcSymbol"),
                        colour = relation.string("colour"),
                        distanceMeters = distance,
                        members = members,
                    )
                } catch (exception: IllegalArgumentException) {
                    return corrupt()
                }
        }
        return try {
            Outcome.Success(RouteRelations(ways = ways, relations = relations))
        } catch (exception: IllegalArgumentException) {
            corrupt()
        }
    }

    private fun parsePoint(element: kotlinx.serialization.json.JsonElement): GeoPoint? {
        val pair = element as? JsonArray ?: return null
        if (pair.size != POINT_COORDINATES) {
            return null
        }
        val longitude = (pair[0] as? JsonPrimitive)?.doubleOrNull ?: return null
        val latitude = (pair[1] as? JsonPrimitive)?.doubleOrNull ?: return null
        if (!longitude.isFinite() || !latitude.isFinite()) {
            return null
        }
        if (latitude !in -MAX_LATITUDE..MAX_LATITUDE || longitude !in -MAX_LONGITUDE..MAX_LONGITUDE) {
            return null
        }
        return GeoPoint(latitude = latitude, longitude = longitude)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    private fun JsonObject.integer(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private fun corrupt(): Outcome<RouteRelations> = Outcome.Failure(AppError.CorruptData)

    private const val POINT_COORDINATES = 2
    private const val MIN_WAY_POINTS = 2
    private const val MAX_LATITUDE = 90.0
    private const val MAX_LONGITUDE = 180.0
}
