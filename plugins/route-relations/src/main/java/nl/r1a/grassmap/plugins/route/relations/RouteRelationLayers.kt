package nl.r1a.grassmap.plugins.route.relations

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.HikingNetwork
import nl.r1a.grassmap.plugin.api.model.LayerId
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerSpec
import nl.r1a.grassmap.plugin.api.model.LayerType
import nl.r1a.grassmap.plugin.api.model.RouteRelations
import nl.r1a.grassmap.plugin.api.model.SlottedLayer
import java.util.Locale
import kotlin.math.min

/**
 * Builds the route-relation layers (T-0418).
 *
 * The band is one line layer over an inline GeoJSON source. Every member way
 * is a feature carrying its relation, network, lane rank and lane count, so a
 * shared way draws one band per route, side by side. The width and offset
 * expressions read those properties, which keeps the whole plugin at two
 * layers plus two symbol layers per lane.
 *
 * The waymark symbols are two symbol layers per lane: a background plate and
 * the OSMC foreground shape, both tinted per feature from the sprite's SDF
 * glyphs. A route without `osmc:symbol`, or with a shape this layer does not
 * carry, keeps its band and draws no mark.
 */
object RouteRelationLayers {
    const val BAND_LAYER_ID: String = "route.relations.band"

    /** The palette and opacity are map-style.md section 3. */
    private val BAND_COLORS =
        mapOf(
            HikingNetwork.International to "#d9483b",
            HikingNetwork.National to "#4a3fd6",
            HikingNetwork.Regional to "#e07a2f",
            HikingNetwork.Local to "#6fbf3a",
        )

    fun build(component: RouteRelations): List<SlottedLayer> {
        val assembled = RouteRelationsGeometry.assemble(component)
        if (assembled.isEmpty()) {
            return emptyList()
        }
        val layers = mutableListOf<SlottedLayer>()
        layers += SlottedLayer(LayerSlot.Routes, bandLayer(assembled))

        val marked =
            assembled.mapNotNull { route ->
                val symbol = OsmcSymbols.parse(route.relation.osmcSymbol) ?: return@mapNotNull null
                route to symbol
            }
        if (marked.isEmpty()) {
            return layers
        }
        val highestLane = min(marked.maxOf { it.first.segments.maxOf { segment -> segment.laneRank } }, MAX_SYMBOL_LANES - 1)
        for (rank in 0..highestLane) {
            val data = symbolFeatureCollection(marked, rank)
            layers += SlottedLayer(LayerSlot.Routes, symbolLayer(data, rank, background = true))
            layers += SlottedLayer(LayerSlot.Routes, symbolLayer(data, rank, background = false))
        }
        return layers
    }

    private fun bandLayer(assembled: List<AssembledRoute>): LayerSpec =
        LayerSpec(
            id = LayerId(BAND_LAYER_ID),
            type = LayerType.Line,
            data = bandFeatureCollection(assembled),
            minZoom = MIN_ZOOM,
            paint =
                buildJsonObject {
                    put("line-color", networkColorExpression())
                    put("line-opacity", 0.55f)
                    put("line-width", scaledExpression("laneScale"))
                    put("line-offset", scaledExpression("laneOffsetUnits"))
                },
            layout =
                buildJsonObject {
                    put("line-cap", "round")
                    put("line-join", "round")
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

    private fun networkColorExpression(): JsonArray =
        buildJsonArray {
            add("match")
            add(property("network"))
            for (network in HikingNetwork.entries) {
                add(network.tag)
                add(BAND_COLORS.getValue(network))
            }
            add(BAND_COLORS.getValue(HikingNetwork.Local))
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

    private fun StringBuilder.appendCoordinates(points: List<GeoPoint>) {
        append('[')
        for ((index, point) in points.withIndex()) {
            if (index > 0) append(',')
            append('[')
                .append(decimal(point.longitude))
                .append(',')
                .append(decimal(point.latitude))
                .append(']')
        }
        append(']')
    }

    private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.7f", value)

    private fun laneOffsetUnits(segment: RelationWayGeometry): Double {
        val center = (segment.laneCount - 1) / 2.0
        return (segment.laneRank - center) / segment.laneCount
    }

    /** The design gives z10 in Standard and z9 in Touring; one overlay serves both. */
    private const val MIN_ZOOM = 9f
    private const val SYMBOL_MIN_ZOOM = 12f
    private const val SYMBOL_LAYER_PREFIX = "route.relations.symbol."
    private const val MAX_SYMBOL_LANES = 6
    private const val SYMBOL_SPACING_DP = 220
    private const val SYMBOL_LANE_OFFSET_DP = 14

    /** The band width in dp at z9, z12 and z14; authored from the style's band idea. */
    private val BAND_WIDTH_STOPS = listOf(9 to 5, 12 to 7, 14 to 9)

    private const val SYMBOL_BACKGROUND_SIZE = 1.0f
    private const val SYMBOL_FOREGROUND_SIZE = 1.0f
}
