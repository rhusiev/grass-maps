package nl.r1a.grassmap.plugins.route.relations

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LayerId
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerSpec
import nl.r1a.grassmap.plugin.api.model.LayerType
import nl.r1a.grassmap.plugin.api.model.NetworkLevel
import nl.r1a.grassmap.plugin.api.model.Route
import nl.r1a.grassmap.plugin.api.model.RouteNetwork
import nl.r1a.grassmap.plugin.api.model.RouteNetworkNode
import nl.r1a.grassmap.plugin.api.model.RouteRelations
import nl.r1a.grassmap.plugin.api.model.SlottedLayer
import java.util.Locale
import kotlin.math.min

/**
 * Builds the route-relation layers (T-0418, T-0419).
 *
 * Each route kind is one band line layer over an inline GeoJSON source. Every
 * member way is a feature carrying its relation, network, lane rank and lane
 * count, so a shared way draws one band per route, side by side. The width and
 * offset expressions read those properties, which keeps the whole plugin at two
 * band layers plus the symbol layers.
 *
 * Hiking and cycling bands have their own palette, opacity and minimum zoom:
 * the design gives hiking z10 in Standard and z9 in Touring, and cycling z12
 * and z11, so one overlay serves both styles. The waymark symbols are two
 * symbol layers per lane: a background plate and the OSMC foreground shape,
 * both tinted per feature from the sprite's SDF glyphs. A route without
 * `osmc:symbol`, or with a shape this layer does not carry, keeps its band and
 * draws no mark.
 *
 * Cycling node-network junctions are a white disc with a green ring and the
 * number as text (T-0419). They are placed at the tagged OSM node, not snapped
 * to a relation line.
 */
object RouteRelationLayers {
    const val BAND_LAYER_ID: String = "route.relations.band"
    const val CYCLING_BAND_LAYER_ID: String = "route.relations.cycling.band"
    const val NODE_LAYER_ID: String = "route.relations.node"
    const val NODE_TEXT_LAYER_ID: String = "route.relations.node.text"

    /** The palette and opacity are map-style.md section 3. */
    private val HIKING_COLORS =
        mapOf(
            NetworkLevel.International to "#d9483b",
            NetworkLevel.National to "#4a3fd6",
            NetworkLevel.Regional to "#e07a2f",
            NetworkLevel.Local to "#6fbf3a",
        )

    /** The cycle band's own palette: international red to local blue, opacity 0.45. */
    private val CYCLING_COLORS =
        mapOf(
            NetworkLevel.International to "#d93030",
            NetworkLevel.National to "#e0602a",
            NetworkLevel.Regional to "#b03ac7",
            NetworkLevel.Local to "#2f5fe0",
        )

    fun build(component: RouteRelations): List<SlottedLayer> {
        val assembled = RouteRelationsGeometry.assemble(component)
        val layers = mutableListOf<SlottedLayer>()
        val hiking = assembled.filter { it.relation.route == Route.Hiking }
        val cycling = assembled.filter { it.relation.route == Route.Cycling }
        if (hiking.isNotEmpty()) {
            layers += SlottedLayer(LayerSlot.Routes, bandLayer(hiking, Route.Hiking))
        }
        if (cycling.isNotEmpty()) {
            layers += SlottedLayer(LayerSlot.Routes, bandLayer(cycling, Route.Cycling))
        }
        if (layers.isEmpty()) {
            return layers
        }

        val marked =
            assembled.mapNotNull { route ->
                val symbol = OsmcSymbols.parse(route.relation.osmcSymbol) ?: return@mapNotNull null
                route to symbol
            }
        if (marked.isNotEmpty()) {
            val highestLane =
                min(
                    marked.maxOf { it.first.segments.maxOf { segment -> segment.laneRank } },
                    MAX_SYMBOL_LANES - 1,
                )
            for (rank in 0..highestLane) {
                val data = symbolFeatureCollection(marked, rank)
                layers += SlottedLayer(LayerSlot.Routes, symbolLayer(data, rank, background = true))
                layers += SlottedLayer(LayerSlot.Routes, symbolLayer(data, rank, background = false))
            }
        }

        if (component.nodes.isNotEmpty()) {
            layers += SlottedLayer(LayerSlot.Routes, nodeLayer(component.nodes))
            layers += SlottedLayer(LayerSlot.Routes, nodeTextLayer(component.nodes))
        }
        return layers
    }

    private fun bandLayer(
        assembled: List<AssembledRoute>,
        route: Route,
    ): LayerSpec {
        val cycling = route == Route.Cycling
        return LayerSpec(
            id = LayerId(if (cycling) CYCLING_BAND_LAYER_ID else BAND_LAYER_ID),
            type = LayerType.Line,
            data = bandFeatureCollection(assembled),
            minZoom = if (cycling) CYCLING_MIN_ZOOM else HIKING_MIN_ZOOM,
            paint =
                buildJsonObject {
                    put(
                        "line-color",
                        networkColorExpression(
                            if (cycling) CYCLING_COLORS else HIKING_COLORS,
                            if (cycling) Route.Cycling else Route.Hiking,
                        ),
                    )
                    put("line-opacity", if (cycling) CYCLING_OPACITY else HIKING_OPACITY)
                    put("line-width", scaledExpression("laneScale"))
                    put("line-offset", scaledExpression("laneOffsetUnits"))
                },
            layout =
                buildJsonObject {
                    put("line-cap", "round")
                    put("line-join", "round")
                },
        )
    }

    private fun nodeLayer(nodes: List<RouteNetworkNode>): LayerSpec =
        LayerSpec(
            id = LayerId(NODE_LAYER_ID),
            type = LayerType.Circle,
            data = nodeFeatureCollection(nodes),
            minZoom = NODE_MIN_ZOOM,
            paint =
                buildJsonObject {
                    put("circle-color", NODE_FILL_COLOR)
                    put("circle-radius", NODE_RADIUS_DP)
                    put("circle-stroke-color", NODE_RING_COLOR)
                    put("circle-stroke-width", NODE_RING_WIDTH_DP)
                },
        )

    private fun nodeTextLayer(nodes: List<RouteNetworkNode>): LayerSpec =
        LayerSpec(
            id = LayerId(NODE_TEXT_LAYER_ID),
            type = LayerType.Symbol,
            data = nodeFeatureCollection(nodes),
            minZoom = NODE_MIN_ZOOM,
            paint =
                buildJsonObject {
                    put("text-color", NODE_TEXT_COLOR)
                },
            layout =
                buildJsonObject {
                    put("text-field", property("ref"))
                    put("text-font", JsonArray(listOf(JsonPrimitive(NODE_FONT))))
                    put("text-size", NODE_TEXT_SIZE)
                    put("text-padding", NODE_TEXT_PADDING)
                    put("symbol-placement", "point")
                    put("symbol-z-order", "source")
                },
        )

    private fun symbolLayer(
        data: String,
        rank: Int,
        background: Boolean,
    ): LayerSpec =
        LayerSpec(
            id = LayerId("$SYMBOL_LAYER_PREFIX${if (background) "bg" else "fg"}.$rank"),
            type = LayerType.Symbol,
            data = data,
            minZoom = SYMBOL_MIN_ZOOM,
            filter = laneFilter(rank),
            paint =
                buildJsonObject {
                    put("icon-color", property("${if (background) "bg" else "fg"}Color"))
                    put("icon-opacity", 1f)
                    put("icon-halo-color", "#ffffff")
                    put("icon-halo-width", if (background) 1f else 0.5f)
                },
            layout =
                buildJsonObject {
                    put("icon-image", property("${if (background) "bg" else "fg"}Icon"))
                    put("icon-size", if (background) SYMBOL_BACKGROUND_SIZE else SYMBOL_FOREGROUND_SIZE)
                    put("icon-rotation-alignment", "viewport")
                    put("icon-allow-overlap", true)
                    put("icon-ignore-placement", true)
                    put("icon-offset", iconOffset(rank))
                    put("symbol-placement", "line")
                    put("symbol-spacing", SYMBOL_SPACING_DP)
                    put("symbol-z-order", "source")
                },
        )

    private fun laneFilter(rank: Int): JsonArray =
        buildJsonArray {
            add("==")
            add(
                buildJsonArray {
                    add("get")
                    add("laneRank")
                },
            )
            add(rank)
        }

    private fun iconOffset(rank: Int): JsonArray =
        buildJsonArray {
            add("literal")
            add(
                buildJsonArray {
                    add(0)
                    add(rank * SYMBOL_LANE_OFFSET_DP)
                },
            )
        }

    private fun networkColorExpression(
        colors: Map<NetworkLevel, String>,
        route: Route,
    ): JsonArray =
        buildJsonArray {
            add("match")
            add(property("network"))
            for (network in RouteNetwork.entries.filter { it.route == route }) {
                add(network.tag)
                add(colors.getValue(network.level))
            }
            add(colors.getValue(NetworkLevel.Local))
        }

    /**
     * A per-feature property multiplied by the band's zoom response.
     *
     * The zoom expression must be the top-level interpolate input, so each stop
     * carries the multiplication; a nested interpolate over zoom is rejected by
     * MapLibre Native.
     */
    private fun scaledExpression(property: String): JsonArray =
        buildJsonArray {
            add("interpolate")
            add(
                buildJsonArray {
                    add("linear")
                },
            )
            add(
                buildJsonArray {
                    add("zoom")
                },
            )
            for ((zoom, width) in BAND_WIDTH_STOPS) {
                add(zoom)
                add(
                    buildJsonArray {
                        add("*")
                        add(property(property))
                        add(width)
                    },
                )
            }
        }

    private fun property(name: String): JsonArray =
        buildJsonArray {
            add("get")
            add(name)
        }

    private fun bandFeatureCollection(assembled: List<AssembledRoute>): String =
        buildString {
            append("{\"type\":\"FeatureCollection\",\"features\":[")
            var first = true
            for (route in assembled) {
                for (segment in route.segments) {
                    if (!first) append(',')
                    first = false
                    append("{\"type\":\"Feature\",\"properties\":{")
                    append("\"relationId\":").append(route.relation.id)
                    append(",\"wayId\":").append(segment.wayId)
                    append(",\"network\":\"").append(route.relation.network.tag).append('"')
                    append(",\"laneRank\":").append(segment.laneRank)
                    append(",\"laneCount\":").append(segment.laneCount)
                    append(",\"laneScale\":").append(decimal(1.0 / segment.laneCount))
                    append(",\"laneOffsetUnits\":").append(decimal(laneOffsetUnits(segment)))
                    append("},\"geometry\":{\"type\":\"LineString\",\"coordinates\":")
                    appendCoordinates(segment.points)
                    append("}}")
                }
            }
            append("]}")
        }

    private fun symbolFeatureCollection(
        marked: List<Pair<AssembledRoute, OsmcSymbol>>,
        rank: Int,
    ): String =
        buildString {
            append("{\"type\":\"FeatureCollection\",\"features\":[")
            var first = true
            for ((route, symbol) in marked) {
                for (segment in route.segments) {
                    if (segment.laneRank != rank) continue
                    if (!first) append(',')
                    first = false
                    append("{\"type\":\"Feature\",\"properties\":{")
                    append("\"laneRank\":").append(segment.laneRank)
                    append(",\"bgIcon\":\"").append(symbol.background.image).append('"')
                    append(",\"bgColor\":\"").append(symbol.background.color).append('"')
                    append(",\"fgIcon\":\"").append(symbol.foreground.image).append('"')
                    append(",\"fgColor\":\"").append(symbol.foreground.color).append('"')
                    append("},\"geometry\":{\"type\":\"LineString\",\"coordinates\":")
                    appendCoordinates(segment.points)
                    append("}}")
                }
            }
            append("]}")
        }

    private fun nodeFeatureCollection(nodes: List<RouteNetworkNode>): String =
        buildString {
            append("{\"type\":\"FeatureCollection\",\"features\":[")
            for ((index, node) in nodes.withIndex()) {
                if (index > 0) append(',')
                append("{\"type\":\"Feature\",\"properties\":{")
                append("\"id\":").append(node.id)
                append(",\"network\":\"").append(node.network.tag).append('"')
                append(",\"ref\":")
                appendJsonString(node.reference)
                append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":")
                appendPoint(node.point)
                append("}}")
            }
            append("]}")
        }

    private fun StringBuilder.appendCoordinates(points: List<GeoPoint>) {
        append('[')
        for ((index, point) in points.withIndex()) {
            if (index > 0) append(',')
            appendPoint(point)
        }
        append(']')
    }

    private fun StringBuilder.appendPoint(point: GeoPoint) {
        append('[')
            .append(decimal(point.longitude))
            .append(',')
            .append(decimal(point.latitude))
            .append(']')
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

    private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.7f", value)

    private fun laneOffsetUnits(segment: RelationWayGeometry): Double {
        val center = (segment.laneCount - 1) / 2.0
        return (segment.laneRank - center) / segment.laneCount
    }

    /** The design gives hiking z10 in Standard and z9 in Touring; one overlay serves both. */
    private const val HIKING_MIN_ZOOM = 9f

    /** The design gives cycling z12 in Standard and z11 in Touring; one overlay serves both. */
    private const val CYCLING_MIN_ZOOM = 11f

    private const val SYMBOL_MIN_ZOOM = 12f

    /** The node-number marker is the r-relations screen's z15 city view. */
    private const val NODE_MIN_ZOOM = 15f
    private const val SYMBOL_LAYER_PREFIX = "route.relations.symbol."
    private const val MAX_SYMBOL_LANES = 6
    private const val SYMBOL_SPACING_DP = 220
    private const val SYMBOL_LANE_OFFSET_DP = 14

    /** The band width in dp at z9, z12 and z14; authored from the style's band idea. */
    private val BAND_WIDTH_STOPS = listOf(9 to 5, 12 to 7, 14 to 9)

    private const val SYMBOL_BACKGROUND_SIZE = 1.0f
    private const val SYMBOL_FOREGROUND_SIZE = 1.0f

    private const val HIKING_OPACITY = 0.55f
    private const val CYCLING_OPACITY = 0.45f

    private const val NODE_FILL_COLOR = "#ffffff"
    private const val NODE_RING_COLOR = "#2e9e44"
    private const val NODE_TEXT_COLOR = "#1d6b2e"
    private const val NODE_RADIUS_DP = 5.5f
    private const val NODE_RING_WIDTH_DP = 2.2f
    private const val NODE_TEXT_SIZE = 10.5f
    private const val NODE_TEXT_PADDING = 1
    private const val NODE_FONT = "Noto Sans Medium"
}
