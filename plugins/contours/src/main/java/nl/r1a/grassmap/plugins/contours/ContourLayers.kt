package nl.r1a.grassmap.plugins.contours

import kotlinx.serialization.json.JsonArray
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
 * The archive stores `ele` in metres and `idx` as a Boolean on index lines. The
 * band layers filter `ele` modulo the interval, so a pack built at 50 m, 20 m
 * or 10 m draws the interval the style asks at that zoom. The Lviv pack and the
 * committed fixture are built at 20 m, so z14 draws the 20 m lines and z12-z13
 * draw the archive's index lines; a finer pack draws its own interval without a
 * style change.
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
 * Builds the overlay layers in draw order: the three interval bands, then the
 * index lines above them, then the elevation labels.
 */
internal object ContourLayers {
    const val SOURCE_LAYER: String = "contours"
    const val INDEX_LAYER_ID: String = "contour-index"
    const val LABEL_LAYER_ID: String = "contour-label"

    const val INTERVAL_50_METERS: Int = 50
    const val INTERVAL_20_METERS: Int = 20
    const val INTERVAL_10_METERS: Int = 10

    const val INDEX_WIDTH_DP: Float = 1.3f
    const val INTERVAL_WIDTH_DP: Float = 1.0f
    const val INTERVAL_10_WIDTH_DP: Float = 0.8f
    const val INDEX_OPACITY: Float = 0.85f
    const val INTERVAL_OPACITY: Float = 0.7f
    const val INTERVAL_10_OPACITY: Float = 0.55f
    const val LABEL_SIZE: Float = 10f
    const val LABEL_HALO_WIDTH: Float = 1.5f
    const val LABEL_FONT: String = "Noto Sans Italic"

    const val MAX_ZOOM: Float = 24f

    fun layers(styleId: StyleId): List<SlottedLayer> {
        val variant = ContourVariant.forStyle(styleId) ?: return emptyList()
        val first = variant.firstZoom
        return listOf(
            interval(INTERVAL_50_METERS, first, first + 1f, INTERVAL_WIDTH_DP, INTERVAL_OPACITY, variant),
            interval(INTERVAL_20_METERS, first + 1f, first + 2f, INTERVAL_WIDTH_DP, INTERVAL_OPACITY, variant),
            interval(INTERVAL_10_METERS, first + 2f, MAX_ZOOM, INTERVAL_10_WIDTH_DP, INTERVAL_10_OPACITY, variant),
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
                JsonArray(
                    listOf(
                        JsonPrimitive("all"),
                        JsonArray(
                            listOf(
                                JsonPrimitive("!="),
                                JsonArray(listOf(JsonPrimitive("get"), JsonPrimitive("idx"))),
                                JsonPrimitive(true),
                            ),
                        ),
                        JsonArray(
                            listOf(
                                JsonPrimitive("=="),
                                JsonArray(
                                    listOf(
                                        JsonPrimitive("%"),
                                        JsonArray(
                                            listOf(
                                                JsonPrimitive("to-number"),
                                                JsonArray(listOf(JsonPrimitive("get"), JsonPrimitive("ele"))),
                                            ),
                                        ),
                                        JsonPrimitive(meters),
                                    ),
                                ),
                                JsonPrimitive(0),
                            ),
                        ),
                    ),
                ),
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
                JsonArray(
                    listOf(
                        JsonPrimitive("=="),
                        JsonArray(listOf(JsonPrimitive("get"), JsonPrimitive("idx"))),
                        JsonPrimitive(true),
                    ),
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
                        JsonArray(
                            listOf(
                                JsonPrimitive("=="),
                                JsonArray(listOf(JsonPrimitive("get"), JsonPrimitive("idx"))),
                                JsonPrimitive(true),
                            ),
                        ),
                    paint =
                        buildJsonObject {
                            put("text-color", variant.palette.labelText)
                            put("text-halo-color", variant.palette.labelHalo)
                            put("text-halo-width", LABEL_HALO_WIDTH)
                        },
                    layout =
                        buildJsonObject {
                            put("text-field", JsonArray(listOf(JsonPrimitive("to-string"), elevation())))
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

    private fun elevation(): JsonArray = JsonArray(listOf(JsonPrimitive("get"), JsonPrimitive("ele")))
}
