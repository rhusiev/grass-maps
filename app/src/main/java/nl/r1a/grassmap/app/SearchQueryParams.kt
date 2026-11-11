package nl.r1a.grassmap.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.model.GeoPoint

/**
 * The parameters one search keystroke sends through the command bus (T-0203,
 * T-0212). The map position is added only when the separate "send my position"
 * allowance is on; the search path itself never invents one.
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
        put("query", query)
        put("sequence", sequence)
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
