package nl.r1a.grassmap.plugins.contours

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.model.LayerId
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerSpec
import nl.r1a.grassmap.plugin.api.model.LayerType
import nl.r1a.grassmap.plugin.api.model.MapSourceIds
import nl.r1a.grassmap.plugin.api.model.SlottedLayer
import nl.r1a.grassmap.plugin.api.model.StyleId

/**
 * The contour overlay for one style variant (design/map-style.md section 3 and
 * research-map-style.md section 7).
 *
 * The archive stores `ele` in metres and `idx` as a Boolean on the 100 m index
 * lines. The pack is built at 10 m from the 90 m DEM: 100 m lines appear from
 * z11, 50 m lines from z12 and all 10 m lines from z13, so the Standard style
 * draws 100 / 50 / 10 m at z12 / z13 / z14 and Touring draws the same ladder
 * one zoom earlier. The filter on `ele` keeps the bands nested: z13 adds the
 * 50 m lines to the 100 m lines, and z14 adds the 10 m lines to both.
 */
internal enum class ContourVariant(
    val styleId: StyleId,
    val palette: ContourPalette,
    val firstZoom: Float,
) {
    StandardLight(
        styleId = StyleId("standard"),
        palette =
            ContourPalette(
                minor = "#dcc2a6",
                index = "#c9a27c",
                labelText = "#222222",
                labelHalo = "#eeeae4",
            ),
        firstZoom = 12f,
    ),
    StandardDark(
        styleId = StyleId("standard-dark"),
        palette =
            ContourPalette(
                minor = "#2e343c",
                index = "#3e4650",
                labelText = "#e3e6ea",
                labelHalo = "#161c26",
            ),
        firstZoom = 12f,
    ),
    TouringLight(
        styleId = StyleId("touring"),
        palette =
            ContourPalette(
                minor = "#d6b48e",
                index = "#bf9364",
                labelText = "#111111",
                labelHalo = "#f3eee8",
            ),
        firstZoom = 11f,
    ),
    TouringDark(
        styleId = StyleId("touring-dark"),
        palette =
            ContourPalette(
                minor = "#24403a",
                index = "#30554c",
                labelText = "#f2f2f2",
                labelHalo = "#10201f",
            ),
        firstZoom = 11f,
    ),
    ;

    val labelZoom: Float
        get() = firstZoom + LABEL_ZOOM_OFFSET

    companion object {
        const val LABEL_ZOOM_OFFSET = 2f

        fun forStyle(styleId: StyleId): ContourVariant? = entries.firstOrNull { it.styleId == styleId }
    }
}

/** The four colours a variant draws with, all from map-style.md section 3. */
internal data class ContourPalette(
    val minor: String,
    val index: String,
    val labelText: String,
    val labelHalo: String,
)

/**
 * Builds the overlay layers in draw order: the 50 m band, then the 10 m band,
 * then the 100 m index lines above them, then the elevation labels. The 50 m
 * lines are thicker than the 10 m lines; the index is thicker still.
 */
internal object ContourLayers {
    const val SOURCE_LAYER: String = "contours"
    const val INDEX_LAYER_ID: String = "contour-index"
    const val LABEL_LAYER_ID: String = "contour-label"

    const val INTERVAL_100_METERS: Int = 100
    const val INTERVAL_50_METERS: Int = 50
    const val INTERVAL_10_METERS: Int = 10

    const val INDEX_WIDTH_DP: Float = 1.3f
    const val INTERVAL_50_WIDTH_DP: Float = 1.0f
    const val INTERVAL_10_WIDTH_DP: Float = 0.6f
    const val INDEX_OPACITY: Float = 0.85f
    const val INTERVAL_50_OPACITY: Float = 0.7f
    const val INTERVAL_10_OPACITY: Float = 0.55f
    const val LABEL_SIZE: Float = 10f
    const val LABEL_HALO_WIDTH: Float = 1.5f
    const val LABEL_FONT: String = "Noto Sans Italic"

    const val MAX_ZOOM: Float = 24f

    fun layers(styleId: StyleId): List<SlottedLayer> {
        val variant = ContourVariant.forStyle(styleId) ?: return emptyList()
        val first = variant.firstZoom
        return listOf(
            interval(
                meters = INTERVAL_50_METERS,
                minZoom = first + 1f,
                maxZoom = MAX_ZOOM,
                width = INTERVAL_50_WIDTH_DP,
                opacity = INTERVAL_50_OPACITY,
                variant = variant,
            ),
            interval(
                meters = INTERVAL_10_METERS,
                minZoom = first + 2f,
                maxZoom = MAX_ZOOM,
                width = INTERVAL_10_WIDTH_DP,
                opacity = INTERVAL_10_OPACITY,
                variant = variant,
            ),
            index(variant),
            label(variant),
        )
    }

    fun intervalLayerId(meters: Int): String = "contour-$meters"

    private fun interval(
        meters: Int,
        minZoom: Float,
        maxZoom: Float,
        width: Float,
        opacity: Float,
        variant: ContourVariant,
    ): SlottedLayer =
        lineLayer(
            id = intervalLayerId(meters),
            minZoom = minZoom,
            maxZoom = maxZoom,
            filter =
                if (meters == INTERVAL_50_METERS) {
                    filterAll(
                        notEquals(field("idx"), JsonPrimitive(true)),
                        equals(modulo(INTERVAL_50_METERS), JsonPrimitive(0)),
                    )
                } else {
                    filterAll(
                        notEquals(field("idx"), JsonPrimitive(true)),
                        equals(modulo(INTERVAL_10_METERS), JsonPrimitive(0)),
                        notEquals(modulo(INTERVAL_50_METERS), JsonPrimitive(0)),
                    )
                },
            paint =
                paint(
                    color = variant.palette.minor,
                    width = width,
                    opacity = opacity,
                ),
        )

    private fun index(variant: ContourVariant): SlottedLayer =
        lineLayer(
            id = INDEX_LAYER_ID,
            minZoom = variant.firstZoom,
            maxZoom = MAX_ZOOM,
            filter =
                filterAll(
                    equals(field("idx"), JsonPrimitive(true)),
                    equals(modulo(INTERVAL_100_METERS), JsonPrimitive(0)),
                ),
            paint =
                paint(
                    color = variant.palette.index,
                    width = INDEX_WIDTH_DP,
                    opacity = INDEX_OPACITY,
                ),
        )

    private fun label(variant: ContourVariant): SlottedLayer =
        SlottedLayer(
            slot = LayerSlot.Overlays,
            spec =
                LayerSpec(
                    id = LayerId(LABEL_LAYER_ID),
                    type = LayerType.Symbol,
                    source = MapSourceIds.CONTOURS,
                    sourceLayer = SOURCE_LAYER,
                    minZoom = variant.labelZoom,
                    maxZoom = MAX_ZOOM,
                    filter =
                        filterAll(
                            equals(field("idx"), JsonPrimitive(true)),
                            equals(modulo(INTERVAL_100_METERS), JsonPrimitive(0)),
                        ),
                    paint =
                        buildJsonObject {
                            put("text-color", variant.palette.labelText)
                            put("text-halo-color", variant.palette.labelHalo)
                            put("text-halo-width", LABEL_HALO_WIDTH)
                        },
                    layout =
                        buildJsonObject {
                            put("text-field", JsonArray(listOf(JsonPrimitive("to-string"), field("ele"))))
                            put("text-font", JsonArray(listOf(JsonPrimitive(LABEL_FONT))))
                            put("text-size", LABEL_SIZE)
                            put("text-padding", 2)
                            put("symbol-placement", "line")
                            put("symbol-z-order", "source")
                            put("text-rotation-alignment", "map")
                        },
                ),
        )

    private fun lineLayer(
        id: String,
        minZoom: Float,
        maxZoom: Float,
        filter: JsonArray,
        paint: JsonObject,
    ): SlottedLayer =
        SlottedLayer(
            slot = LayerSlot.Overlays,
            spec =
                LayerSpec(
                    id = LayerId(id),
                    type = LayerType.Line,
                    source = MapSourceIds.CONTOURS,
                    sourceLayer = SOURCE_LAYER,
                    minZoom = minZoom,
                    maxZoom = maxZoom,
                    filter = filter,
                    paint = paint,
                ),
        )

    private fun paint(
        color: String,
        width: Float,
        opacity: Float,
    ): JsonObject =
        buildJsonObject {
            put("line-color", color)
            put("line-width", width)
            put("line-opacity", opacity)
        }

    private fun filterAll(vararg conditions: JsonElement): JsonArray = JsonArray(listOf(JsonPrimitive("all")) + conditions)

    private fun equals(
        left: JsonElement,
        right: JsonElement,
    ): JsonArray = JsonArray(listOf(JsonPrimitive("=="), left, right))

    private fun notEquals(
        left: JsonElement,
        right: JsonElement,
    ): JsonArray = JsonArray(listOf(JsonPrimitive("!="), left, right))

    private fun field(name: String): JsonArray = JsonArray(listOf(JsonPrimitive("get"), JsonPrimitive(name)))

    private fun modulo(interval: Int): JsonArray =
        JsonArray(
            listOf(
                JsonPrimitive("%"),
                JsonArray(listOf(JsonPrimitive("to-number"), field("ele"))),
                JsonPrimitive(interval),
            ),
        )
}
