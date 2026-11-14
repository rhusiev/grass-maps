package nl.r1a.grassmap.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.model.GeoPoint

/**
 * The parameters one search request sends through the command bus (T-0203,
 * T-0212, T-0207). The map position is added only when the separate "send my
 * position" allowance is on; the search path itself never invents one.
 */
internal fun searchKeystrokeParams(
    query: String,
    sequence: Long,
    index: String,
    limit: Int,
    position: GeoPoint?,
    positionAllowed: Boolean,
): JsonObject =
    buildJsonObject {
        putSearchRequest(query, index, limit, position, positionAllowed)
        put("sequence", sequence)
    }

/** A submitted field: a keystroke's request plus the flag that stores it. */
internal fun searchSubmitParams(
    query: String,
    sequence: Long,
    index: String,
    limit: Int,
    position: GeoPoint?,
    positionAllowed: Boolean,
): JsonObject =
    buildJsonObject {
        putSearchRequest(query, index, limit, position, positionAllowed)
        put("sequence", sequence)
        put("submit", true)
    }

/** A picked result row: its text is stored, and no new search runs. */
internal fun searchPickParams(
    query: String,
    index: String,
    limit: Int,
    position: GeoPoint?,
    positionAllowed: Boolean,
): JsonObject =
    buildJsonObject {
        putSearchRequest(query, index, limit, position, positionAllowed)
        put("pick", true)
    }

private fun JsonObjectBuilder.putSearchRequest(
    query: String,
    index: String,
    limit: Int,
    position: GeoPoint?,
    positionAllowed: Boolean,
) {
    put("query", query)
    put("index", index)
    put("limit", limit)
    if (positionAllowed && position != null) {
        put(
            "position",
            buildJsonObject {
                put("lat", position.latitude)
                put("lon", position.longitude)
            },
        )
    }
}
