package nl.r1a.grassmap.routing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteInstruction
import nl.r1a.grassmap.plugin.api.provider.RouteSegment

/**
 * Reads the parts of a Valhalla `/route` response the shared model needs.
 * Distances arrive in kilometres because the request asks for that unit.
 */
internal object ValhallaResponse {
    private const val METRES_PER_KILOMETRE = 1_000.0

    fun parse(raw: String): Outcome<List<Route>> {
        val root = parseObject(raw) ?: return Outcome.Failure(AppError.CorruptData)
        val trip = root.objectValue("trip") ?: return Outcome.Failure(AppError.CorruptData)
        val primary =
            when (val parsed = parseTrip(trip, raw)) {
                is Outcome.Success -> parsed.value
                is Outcome.Failure -> return parsed
            }
        val alternates =
            root.arrayValue("alternates").orEmpty().mapNotNull { element ->
                (element as? JsonObject)?.objectValue("trip")?.let { alternateTrip ->
                    (parseTrip(alternateTrip, null) as? Outcome.Success)?.value
                }
            }
        return Outcome.Success(listOf(primary) + alternates)
    }

    fun parseTrip(
        trip: JsonObject,
        raw: String?,
    ): Outcome<Route> {
        val legs = trip.arrayValue("legs").orEmpty().filterIsInstance<JsonObject>()
        if (legs.isEmpty()) {
            return Outcome.Failure(AppError.CorruptData)
        }
        val summary = trip.objectValue("summary")
        val geometry = mutableListOf<GeoPoint>()
        val segments = mutableListOf<RouteSegment>()
        val instructions = mutableListOf<RouteInstruction>()
        var distanceMeters = 0.0
        var durationSeconds = 0.0
        var distanceAlongRouteMeters = 0.0

        for (leg in legs) {
            val legSummary = leg.objectValue("summary")
            val legDistance = (legSummary?.doubleValue("length") ?: 0.0) * METRES_PER_KILOMETRE
            val legDuration = legSummary?.doubleValue("time") ?: 0.0
            val startIndex = geometry.size
            val shape = leg.stringValue("shape")
            if (!shape.isNullOrEmpty()) {
                for (point in Polyline6.decode(shape)) {
                    if (geometry.lastOrNull() != point) {
                        geometry += point
                    }
                }
            }
            segments +=
                RouteSegment(
                    startIndex = startIndex,
                    endIndex = geometry.size,
                    distanceMeters = legDistance,
                    durationSeconds = legDuration,
                )
            distanceMeters += legDistance
            durationSeconds += legDuration

            for (maneuver in leg.arrayValue("maneuvers").orEmpty().filterIsInstance<JsonObject>()) {
                instructions +=
                    RouteInstruction(
                        text = maneuver.stringValue("instruction").orEmpty(),
                        streetName = maneuver.stringValue("street_name"),
                        distanceAlongRouteMeters = distanceAlongRouteMeters,
                        durationSeconds = maneuver.doubleValue("time") ?: 0.0,
                        maneuver = maneuver.stringValue("type") ?: maneuver.intValue("type")?.toString(),
                    )
                distanceAlongRouteMeters +=
                    (maneuver.doubleValue("length") ?: 0.0) * METRES_PER_KILOMETRE
            }
        }

        if (geometry.size < 2) {
            return Outcome.Failure(AppError.CorruptData)
        }
        return Outcome.Success(
            Route(
                geometry = geometry,
                distanceMeters =
                    summary?.doubleValue("length")?.times(METRES_PER_KILOMETRE)
                        ?: distanceMeters,
                durationSeconds = summary?.doubleValue("time") ?: durationSeconds,
                segments = segments,
                instructions = instructions,
                rawResponse = raw,
            ),
        )
    }

    private fun parseObject(raw: String): JsonObject? = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()

    private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arrayValue(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.stringValue(key: String): String? {
        val element = this[key] as? JsonPrimitive ?: return null
        return element.contentOrNull
    }

    private fun JsonObject.doubleValue(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.intValue(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}
