package nl.r1a.grassmap.plugins.valhalla

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
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
import nl.r1a.grassmap.plugin.api.provider.RouteAttributes
import nl.r1a.grassmap.plugin.api.provider.RouteSegment

/**
 * Turns a routed line into per-edge segments with the five v1 attributes.
 *
 * Valhalla's `/route` answer carries no per-edge tags, so the line is sent back
 * to `/trace_attributes` with `map_snap`: Valhalla matches the line to graph
 * edges and returns one record per edge. The line is cut at those edges, which
 * is why the attributes arrive after the route is already shown.
 */
internal object ValhallaTrace {
    private const val METRES_PER_KILOMETRE = 1_000.0

    /** The pack's `trace.max_distance`; a chunk stays well below it. */
    internal const val MAX_CHUNK_METRES = 50_000.0

    private const val MAX_CHUNK_POINTS = 4_000

    private val FILTERED_ATTRIBUTES =
        listOf(
            "edge.length",
            "edge.surface",
            "edge.use",
            "edge.road_class",
            "edge.sac_scale",
            "edge.access_restriction",
        )

    /** One trace request body for [points], which must hold at least two points. */
    fun request(
        points: List<GeoPoint>,
        profile: ValhallaProfile,
    ): String =
        buildJsonObject {
            put("costing", profile.costing)
            putJsonObject("costing_options") {
                put(profile.costing, profile.costingOptions)
            }
            put("shape_match", "map_snap")
            put("encoded_polyline", Polyline6.encode(points))
            put("units", "kilometers")
            putJsonObject("filters") {
                put("action", "include")
                putJsonArray("attributes") {
                    for (name in FILTERED_ATTRIBUTES) {
                        add(name)
                    }
                }
            }
        }.toString()

    /** Splits [points] into traces short enough for one `trace_attributes` call. */
    fun chunks(points: List<GeoPoint>): List<List<GeoPoint>> {
        if (points.size < 2) {
            return emptyList()
        }
        val result = mutableListOf<List<GeoPoint>>()
        var start = 0
        var distance = 0.0
        for (index in 1 until points.size) {
            distance += ValhallaRequest.distanceMetres(points[index - 1], points[index])
            val tooLong = distance > MAX_CHUNK_METRES
            val tooMany = index - start >= MAX_CHUNK_POINTS
            val last = index == points.size - 1
            if (last) {
                result += points.subList(start, index + 1).toList()
            } else if ((tooLong || tooMany) && index > start + 1) {
                // End before the point that would push the chunk over the limit;
                // the next chunk starts there, so the chunks share it.
                result += points.subList(start, index).toList()
                start = index - 1
                distance = ValhallaRequest.distanceMetres(points[start], points[index])
            }
        }
        return result
    }

    /** Reads `edges` from one trace response. */
    fun edges(raw: String): Outcome<List<TraceEdge>> {
        val root =
            runCatching {
                kotlinx.serialization.json.Json
                    .parseToJsonElement(raw) as? JsonObject
            }.getOrNull() ?: return Outcome.Failure(AppError.CorruptData)
        val array = root["edges"] as? JsonArray ?: return Outcome.Failure(AppError.CorruptData)
        val edges =
            array.mapNotNull { element ->
                val edge = element as? JsonObject ?: return@mapNotNull null
                val length = edge.doubleValue("length") ?: return@mapNotNull null
                TraceEdge(
                    lengthMeters = length * METRES_PER_KILOMETRE,
                    values =
                        mapOf(
                            RouteAttributes.SURFACE to AttributeValue.TextValue(edge.stringValue("surface") ?: UNKNOWN),
                            RouteAttributes.HIGHWAY to AttributeValue.TextValue(highwayOf(edge)),
                            RouteAttributes.SAC_SCALE to AttributeValue.TextValue(sacScaleOf(edge.intValue("sac_scale"))),
                            RouteAttributes.ACCESS to AttributeValue.TextValue(accessOf(edge)),
                        ),
                )
            }
        if (edges.isEmpty()) {
            return Outcome.Failure(AppError.CorruptData)
        }
        return Outcome.Success(edges)
    }

    /**
     * Cuts [route] into one segment per edge. Each segment keeps its half-open
     * shape range into the route geometry; a duration is split by distance,
     * because the trace answer carries no per-edge time.
     */
    fun segments(
        route: Route,
        edges: List<TraceEdge>,
    ): List<RouteSegment> {
        val points = route.geometry
        if (points.size < 2) {
            return emptyList()
        }
        val cumulative = DoubleArray(points.size)
        for (index in 1 until points.size) {
            cumulative[index] = cumulative[index - 1] + ValhallaRequest.distanceMetres(points[index - 1], points[index])
        }
        val total = cumulative.last().coerceAtLeast(1.0)
        val edgeTotal = edges.sumOf { it.lengthMeters }.coerceAtLeast(1.0)
        val secondsPerMetre = route.durationSeconds / edgeTotal

        val segments = mutableListOf<RouteSegment>()
        var travelled = 0.0
        var startIndex = 0
        for (edge in edges) {
            travelled += edge.lengthMeters
            val endIndex = indexAt(cumulative, travelled)
            if (endIndex <= startIndex) {
                startIndex = endIndex
                continue
            }
            segments +=
                RouteSegment(
                    startIndex = startIndex,
                    endIndex = endIndex,
                    distanceMeters = edge.lengthMeters,
                    durationSeconds = edge.lengthMeters * secondsPerMetre,
                    attributes = edge.values,
                )
            startIndex = endIndex
        }
        if (segments.isEmpty()) {
            return listOf(
                RouteSegment(
                    startIndex = 0,
                    endIndex = points.size - 1,
                    distanceMeters = total,
                    durationSeconds = route.durationSeconds,
                ),
            )
        }
        return segments
    }

    private fun indexAt(
        cumulative: DoubleArray,
        distance: Double,
    ): Int {
        for (index in cumulative.indices) {
            if (cumulative[index] >= distance) {
                return index.coerceAtLeast(1)
            }
        }
        return cumulative.size - 1
    }

    /** `use` first, because it separates a path from a residential street. */
    private fun highwayOf(edge: JsonObject): String {
        val use = edge.stringValue("use")
        if (use == null || use == ROAD) {
            val roadClass = edge.stringValue("road_class")
            return if (roadClass == SERVICE_OTHER) SERVICE else roadClass ?: UNKNOWN
        }
        return HIGHWAY_BY_USE[use] ?: use
    }

    private fun accessOf(edge: JsonObject): String {
        val restrictions = edge["access_restriction"] as? JsonArray ?: return YES
        val first = (restrictions.firstOrNull() as? JsonPrimitive)?.contentOrNull
        return first ?: YES
    }

    private fun sacScaleOf(value: Int?): String = SAC_SCALE_NAMES.getOrElse(value ?: 0) { UNKNOWN }

    private fun JsonObject.stringValue(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.doubleValue(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.intValue(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    /** One matched edge: its length and the four attributes the trace carries. */
    data class TraceEdge(
        val lengthMeters: Double,
        val values: Map<String, AttributeValue>,
    )

    private const val UNKNOWN = "unknown"
    private const val YES = "yes"
    private const val ROAD = "road"
    private const val SERVICE = "service"
    private const val SERVICE_OTHER = "service_other"

    /** OSM highway vocabulary for Valhalla's `edge.use` enum. */
    private val HIGHWAY_BY_USE =
        mapOf(
            "walkway" to "footway",
            "footway" to "footway",
            "path" to "path",
            "steps" to "steps",
            "pedestrian" to "pedestrian",
            "service_road" to "service",
            "alley" to "service",
            "driveway" to "driveway",
            "parking" to "parking",
            "track" to "track",
            "cycleway" to "cycleway",
            "mountain_bike" to "path",
            "bridleway" to "bridleway",
            "ramp" to "service",
            "turn_channel" to "service",
            "emergency_access" to "service",
        )

    /** Valhalla's `SacScale` enum, in the OSM spelling. */
    private val SAC_SCALE_NAMES =
        listOf(
            "none",
            "hiking",
            "mountain_hiking",
            "demanding_mountain_hiking",
            "alpine_hiking",
            "demanding_alpine_hiking",
            "difficult_alpine_hiking",
        )
}
