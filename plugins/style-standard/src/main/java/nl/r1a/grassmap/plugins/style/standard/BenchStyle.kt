package nl.r1a.grassmap.plugins.style.standard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import nl.r1a.grassmap.plugin.api.model.LayerId
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerSpec
import nl.r1a.grassmap.plugin.api.model.LayerType
import nl.r1a.grassmap.plugin.api.model.MapSource
import nl.r1a.grassmap.plugin.api.model.MapSourceIds
import nl.r1a.grassmap.plugin.api.model.SlottedLayer
import nl.r1a.grassmap.plugin.api.model.SourceId
import nl.r1a.grassmap.plugin.api.model.StyleId

internal enum class StyleVariant(
    val id: StyleId,
) {
    Standard(StyleId("standard")),
    Touring(StyleId("touring")),
}

/**
 * The T-0020 style spike document: the phase-A dense Protomaps-derived bench
 * style, split into a base document and layer fragments. It proves the
 * Compose-layer model; the authored Standard and Touring palettes are T-0603.
 *
 * Layers are split across the logical sources by zoom: `world` for z0-z7 and
 * `region` for z8-z14.
 */
internal class BenchStyle private constructor(
    val baseStyleJson: String,
    private val layers: List<JsonObject>,
) {
    fun baseStyle(): String = baseStyleJson

    fun layersFor(variant: StyleVariant): List<SlottedLayer> {
        val result = mutableListOf<SlottedLayer>()
        for (layer in layers) {
            val shaped = if (variant == StyleVariant.Touring) touring(layer) else layer
            result += splitBySource(shaped)
        }
        result += ContourOverlay.layers
        return result
    }

    private fun splitBySource(layer: JsonObject): List<SlottedLayer> {
        val type = layerType(layer) ?: return emptyList()
        val id = layer.string("id") ?: return emptyList()
        if (type == LayerType.Background) {
            return listOf(SlottedLayer(LayerSlot.Base, layer.toSpec(type, id, null, 0f, 24f)))
        }
        val minZoom = layer.number("minzoom") ?: 0f
        val maxZoom = layer.number("maxzoom") ?: 24f
        val result = mutableListOf<SlottedLayer>()
        if (minZoom < REGION_START_ZOOM) {
            result +=
                SlottedLayer(
                    LayerSlot.Base,
                    layer.toSpec(
                        type = type,
                        id = "${id}__world",
                        source = MapSourceIds.WORLD,
                        minZoom = minZoom,
                        maxZoom = minOf(maxZoom, REGION_START_ZOOM),
                    ),
                )
        }
        if (maxZoom > REGION_START_ZOOM) {
            result +=
                SlottedLayer(
                    LayerSlot.Base,
                    layer.toSpec(
                        type = type,
                        id = "${id}__region",
                        source = MapSourceIds.REGION,
                        minZoom = maxOf(minZoom, REGION_START_ZOOM),
                        maxZoom = maxZoom,
                    ),
                )
        }
        return result
    }

    private fun JsonObject.toSpec(
        type: LayerType,
        id: String,
        source: SourceId?,
        minZoom: Float,
        maxZoom: Float,
    ): LayerSpec =
        LayerSpec(
            id = LayerId(id),
            type = type,
            source = source,
            sourceLayer = string("source-layer"),
            minZoom = minZoom,
            maxZoom = maxZoom,
            filter = this["filter"],
            paint = this["paint"] as? JsonObject ?: JsonObject(emptyMap()),
            layout = this["layout"] as? JsonObject ?: JsonObject(emptyMap()),
        )

    private fun touring(layer: JsonObject): JsonObject {
        val id = layer.string("id") ?: return layer
        val type = layer.string("type")
        val paint = layer["paint"] as? JsonObject ?: return layer
        val next = paint.toMutableMap()
        when {
            id == "background" -> {
                next["background-color"] = JsonPrimitive(TouringColors.BACKGROUND)
            }

            id == "earth" -> {
                next["fill-color"] = JsonPrimitive(TouringColors.EARTH)
            }

            type == "fill" && (id == "water" || id.startsWith("water_")) -> {
                next["fill-color"] = JsonPrimitive(TouringColors.WATER)
            }

            type == "fill" && (id.contains("park") || id.contains("green")) -> {
                next["fill-color"] = JsonPrimitive(TouringColors.GREEN)
            }

            type == "line" && (id.contains("major") || id.contains("highway")) -> {
                next["line-color"] = JsonPrimitive(TouringColors.ROAD)
            }
        }
        return JsonObject(layer + ("paint" to JsonObject(next)))
    }

    companion object {
        private const val REGION_START_ZOOM = 8f

        fun parse(json: String): BenchStyle {
            val root = Json.parseToJsonElement(json).jsonObject
            val base =
                buildJsonObject {
                    put("version", root["version"] ?: JsonPrimitive(8))
                    root["glyphs"]?.let { put("glyphs", it) }
                    root["sprite"]?.let { put("sprite", it) }
                    putJsonArray("layers") {}
                }
            val layers = (root["layers"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            return BenchStyle(baseStyleJson = base.toString(), layers = layers)
        }

        val sources: List<MapSource> =
            listOf(
                MapSource(MapSourceIds.WORLD, minZoom = 0, maxZoom = 7),
                MapSource(MapSourceIds.REGION, minZoom = 8, maxZoom = 14),
                MapSource(MapSourceIds.CONTOURS, minZoom = 9, maxZoom = 14),
            )
    }
}

/**
 * Contour overlays shared by every style variant. The file is optional; the
 * host skips these layers when the source is not mounted.
 */
internal object ContourOverlay {
    val layers: List<SlottedLayer> =
        listOf(
            contour(id = "contour-index", minZoom = 11f, width = 1.3f, opacity = 0.85f),
            contour(id = "contour-20", minZoom = 13f, width = 1.0f, opacity = 0.7f),
            contour(id = "contour-10", minZoom = 14f, width = 0.8f, opacity = 0.55f),
        )

    private fun contour(
        id: String,
        minZoom: Float,
        width: Float,
        opacity: Float,
    ): SlottedLayer =
        SlottedLayer(
            slot = LayerSlot.Overlays,
            spec =
                LayerSpec(
                    id = LayerId(id),
                    type = LayerType.Line,
                    source = MapSourceIds.CONTOURS,
                    sourceLayer = "contours",
                    minZoom = minZoom,
                    maxZoom = 14f,
                    filter =
                        JsonArray(
                            listOf(
                                JsonPrimitive("=="),
                                JsonPrimitive("idx"),
                                JsonPrimitive(if (id == "contour-index") 1 else 0),
                            ),
                        ),
                    paint =
                        buildJsonObject {
                            put("line-color", "#a9774a")
                            put("line-width", width)
                            put("line-opacity", opacity)
                        },
                ),
        )
}

private fun layerType(layer: JsonObject): LayerType? =
    when (layer.string("type")) {
        "background" -> LayerType.Background
        "fill" -> LayerType.Fill
        "line" -> LayerType.Line
        "symbol" -> LayerType.Symbol
        "circle" -> LayerType.Circle
        "raster" -> LayerType.Raster
        "hillshade" -> LayerType.Hillshade
        else -> null
    }

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.number(key: String): Float? = (this[key] as? JsonPrimitive)?.floatOrNull

private object TouringColors {
    const val BACKGROUND = "#ece9e1"
    const val EARTH = "#f2eee4"
    const val WATER = "#b8d5dd"
    const val GREEN = "#d5e9c8"
    const val ROAD = "#d9a25c"
}
