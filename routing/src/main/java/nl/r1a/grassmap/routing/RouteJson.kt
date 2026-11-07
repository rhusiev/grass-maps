package nl.r1a.grassmap.routing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.AttributeValue
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteInstruction
import nl.r1a.grassmap.plugin.api.provider.RouteSegment

/**
 * The stable JSON form of [Route] for logs, fixtures and the control channel.
 * The engine's raw response is not encoded: it is a debugging attachment, not
 * part of the shared model.
 */
object RouteJson {
    fun encode(route: Route): String =
        buildJsonObject {
            putJsonArray("geometry") {
                for (point in route.geometry) {
                    addJsonObject {
                        put("lat", point.latitude)
                        put("lon", point.longitude)
                    }
                }
            }
            put("distanceMeters", route.distanceMeters)
            put("durationSeconds", route.durationSeconds)
            putJsonArray("segments") {
                for (segment in route.segments) {
                    addJsonObject {
                        put("startIndex", segment.startIndex)
                        put("endIndex", segment.endIndex)
                        put("distanceMeters", segment.distanceMeters)
                        put("durationSeconds", segment.durationSeconds)
                        putJsonObject("attributes") {
                            for ((key, value) in segment.attributes) {
                                when (value) {
                                    is AttributeValue.NumberValue -> put(key, value.value)
                                    is AttributeValue.TextValue -> put(key, value.value)
                                    is AttributeValue.BooleanValue -> put(key, value.value)
                                }
                            }
                        }
                    }
                }
            }
            putJsonArray("instructions") {
                for (instruction in route.instructions) {
                    addJsonObject {
                        put("text", instruction.text)
                        instruction.streetName?.let { put("streetName", it) }
                        put("distanceAlongRouteMeters", instruction.distanceAlongRouteMeters)
                        put("durationSeconds", instruction.durationSeconds)
                        instruction.maneuver?.let { put("maneuver", it) }
                    }
                }
            }
        }.toString()

    fun decode(json: String): Outcome<Route> {
        val root =
            runCatching { Json.parseToJsonElement(json) as? JsonObject }
                .getOrNull()
                ?: return Outcome.Failure(AppError.InvalidInput)
        val geometry =
            root
                .array("geometry")
                ?.mapNotNull { element ->
                    (element as? JsonObject)?.let { point ->
                        val latitude = point.number("lat") ?: return@mapNotNull null
                        val longitude = point.number("lon") ?: return@mapNotNull null
                        GeoPoint(latitude, longitude)
                    }
                }.orEmpty()
        val distance = root.number("distanceMeters") ?: return Outcome.Failure(AppError.InvalidInput)
        val duration = root.number("durationSeconds") ?: return Outcome.Failure(AppError.InvalidInput)
        if (geometry.size < 2) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val segments =
            root
                .array("segments")
                .orEmpty()
                .mapNotNull { element ->
                    val segment = element as? JsonObject ?: return@mapNotNull null
                    val startIndex = segment.int("startIndex") ?: return@mapNotNull null
                    val endIndex = segment.int("endIndex") ?: return@mapNotNull null
                    RouteSegment(
                        startIndex = startIndex,
                        endIndex = endIndex,
                        distanceMeters = segment.number("distanceMeters") ?: 0.0,
                        durationSeconds = segment.number("durationSeconds") ?: 0.0,
                        attributes = readAttributes(segment.objectValue("attributes")),
                    )
                }
        val instructions =
            root
                .array("instructions")
                .orEmpty()
                .mapNotNull { element ->
                    val instruction = element as? JsonObject ?: return@mapNotNull null
                    RouteInstruction(
                        text = instruction.string("text").orEmpty(),
                        streetName = instruction.string("streetName"),
                        distanceAlongRouteMeters =
                            instruction.number("distanceAlongRouteMeters") ?: 0.0,
                        durationSeconds = instruction.number("durationSeconds") ?: 0.0,
                        maneuver = instruction.string("maneuver"),
                    )
                }
        return Outcome.Success(
            Route(
                geometry = geometry,
                distanceMeters = distance,
                durationSeconds = duration,
                segments = segments,
                instructions = instructions,
            ),
        )
    }

    private fun readAttributes(attributes: JsonObject?): Map<String, AttributeValue> =
        attributes
            .orEmpty()
            .mapNotNull { (key, element) ->
                val primitive = element as? JsonPrimitive ?: return@mapNotNull null
                val value =
                    when {
                        primitive.isString -> {
                            AttributeValue.TextValue(primitive.content)
                        }

                        primitive.booleanOrNull != null -> {
                            AttributeValue.BooleanValue(primitive.booleanOrNull == true)
                        }

                        primitive.doubleOrNull != null -> {
                            AttributeValue.NumberValue(primitive.doubleOrNull ?: 0.0)
                        }

                        else -> {
                            return@mapNotNull null
                        }
                    }
                key to value
            }.toMap()

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject
}
