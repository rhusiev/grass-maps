package nl.r1a.grassmap.plugins.style.standard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.model.LayerId
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerSpec
import nl.r1a.grassmap.plugin.api.model.LayerType
import nl.r1a.grassmap.plugin.api.model.MapSource
import nl.r1a.grassmap.plugin.api.model.MapSourceIds
import nl.r1a.grassmap.plugin.api.model.SlottedLayer
import nl.r1a.grassmap.plugin.api.model.SourceId
import nl.r1a.grassmap.plugin.api.model.StyleId

/**
 * The authored Standard style, split into one layer list and a light/dark
 * palette pair. Every colour in the list is a palette token, so both variants
 * have the same layers, the same draw order and the same cost.
 *
 * Layers are split across the logical sources by zoom: `world` for z0-z7 and
 * `region` for z8-z14. Contour overlays are added to every variant.
 *
 * Symbol text and halo colours are palette values written as `rgb()` arrays.
 * The current text path renders a hex text colour with its green channel
 * zeroed, which turns light dark-theme labels magenta; the render-path fix is
 * reported separately, so the style data avoids the broken form.
 */
internal class StandardStyle private constructor(
    private val baseStyleJson: String,
    private val variantPalettes: Map<StyleId, JsonObject>,
    private val layers: List<JsonObject>,
) {
    fun baseStyle(): String = baseStyleJson

    fun variants(): List<StyleId> = variantPalettes.keys.toList()

    fun layersFor(styleId: StyleId): List<SlottedLayer> {
        val palette = variantPalettes[styleId] ?: return emptyList()
        val result = mutableListOf<SlottedLayer>()
        for (layer in layers) {
            result += splitBySource(resolve(layer, palette) as? JsonObject ?: continue)
        }
        result += contourOverlay(palette)
        return result
    }

    private fun splitBySource(layer: JsonObject): List<SlottedLayer> {
        val type = layerType(layer) ?: return emptyList()
        val id = layer.string("id") ?: return emptyList()
        if (type == LayerType.Background) {
            return listOf(
                SlottedLayer(
                    LayerSlot.Base,
                    layer.toSpec(type, id, null, 0f, 24f),
                ),
            )
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

    private fun contourOverlay(palette: JsonObject): List<SlottedLayer> {
        val minor = palette["contour_minor"] ?: return emptyList()
        val index = palette["contour_index"] ?: return emptyList()
        return listOf(
            contour("contour-index", 11f, 1.3f, 0.85f, index),
            contour("contour-20", 13f, 1.0f, 0.7f, minor),
            contour("contour-10", 14f, 0.8f, 0.55f, minor),
        )
    }

    private fun contour(
        id: String,
        minZoom: Float,
        width: Float,
        opacity: Float,
        color: JsonElement,
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
                                JsonPrimitive(id == "contour-index"),
                            ),
                        ),
                    paint =
                        buildJsonObject {
                            put("line-color", color)
                            put("line-width", width)
                            put("line-opacity", opacity)
                        },
                ),
        )

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

    companion object {
        private const val REGION_START_ZOOM = 8f
        private const val TOKEN_PREFIX = "@"

        fun parse(json: String): StandardStyle {
            val root = Json.parseToJsonElement(json).jsonObject
            val base =
                buildJsonObject {
                    put("version", root["version"] ?: JsonPrimitive(8))
                    root["glyphs"]?.let { put("glyphs", it) }
                    root["sprite"]?.let { put("sprite", it) }
                    put("layers", JsonArray(emptyList()))
                }
            val palettes = root["palettes"]?.jsonObject ?: JsonObject(emptyMap())
            val styles = root["styles"]?.jsonObject ?: JsonObject(emptyMap())
            val resolved = linkedMapOf<StyleId, JsonObject>()
            for ((id, paletteName) in styles) {
                val name = (paletteName as? JsonPrimitive)?.contentOrNull ?: continue
                val palette = palettes[name]?.jsonObject ?: continue
                resolved[StyleId(id)] = palette
            }
            val layers = (root["layers"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            return StandardStyle(
                baseStyleJson = base.toString(),
                variantPalettes = resolved,
                layers = layers,
            )
        }

        private fun resolve(
            element: JsonElement,
            palette: JsonObject,
        ): JsonElement =
            when (element) {
                is JsonObject -> {
                    JsonObject(element.mapValues { resolve(it.value, palette) })
                }

                is JsonArray -> {
                    JsonArray(element.map { resolve(it, palette) })
                }

                is JsonPrimitive -> {
                    val content = element.contentOrNull
                    if (content != null && content.startsWith(TOKEN_PREFIX)) {
                        palette[content.removePrefix(TOKEN_PREFIX)] ?: element
                    } else {
                        element
                    }
                }
            }

        val sources: List<MapSource> =
            listOf(
                MapSource(MapSourceIds.WORLD, minZoom = 0, maxZoom = 7),
                MapSource(MapSourceIds.REGION, minZoom = 8, maxZoom = 14),
                MapSource(MapSourceIds.CONTOURS, minZoom = 9, maxZoom = 14),
            )
    }
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
