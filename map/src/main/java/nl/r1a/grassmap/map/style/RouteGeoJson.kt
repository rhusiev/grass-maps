package nl.r1a.grassmap.map.style

import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import nl.r1a.grassmap.plugin.api.provider.Route

/**
 * The route as one GeoJSON FeatureCollection with a single LineString. Kept out
 * of the Compose layer file so the encoding is testable without a map engine.
 */
internal fun Route.toFeatureCollection(): String =
    buildJsonObject {
        put("type", "FeatureCollection")
        putJsonArray("features") {
            addJsonObject {
                put("type", "Feature")
                putJsonObject("properties") {}
                putJsonObject("geometry") {
                    put("type", "LineString")
                    putJsonArray("coordinates") {
                        for (point in geometry) {
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
