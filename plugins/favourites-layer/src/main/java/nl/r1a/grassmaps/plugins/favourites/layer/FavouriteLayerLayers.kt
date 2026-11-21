package nl.r1a.grassmaps.plugins.favourites.layer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmaps.plugin.api.model.LayerId
import nl.r1a.grassmaps.plugin.api.model.LayerSlot
import nl.r1a.grassmaps.plugin.api.model.LayerSpec
import nl.r1a.grassmaps.plugin.api.model.LayerType
import nl.r1a.grassmaps.plugin.api.model.SlottedLayer

/**
 * Compiles the saved places into MapLibre style layers in the markers slot (T-0215).
 *
 * One inline FeatureCollection carries one Point per visible saved place, with
 * its name, icon and effective shape in the properties. The shape background
 * draws the sprite the shape names, the per-point icon draws the point's own
 * sprite, and the name layer draws only when the control is on. A hidden group
 * has no features, so this builder returns no layers for it at all.
 *
 * The name paint uses round 9's ink and map tokens with a halo, so the label
 * stays readable over both map themes without a second set of layers.
 */
object FavouriteLayerLayers {
    const val SHAPE_LAYER_ID = "favourites.layer.shape"
    const val ICON_LAYER_ID = "favourites.layer.icon"
    const val NAME_LAYER_ID = "favourites.layer.name"

    private const val MIN_ZOOM = 0f
    private const val MAX_ZOOM = 24f

    fun build(
        library: FavouriteLayerLibrary,
        settings: FavouriteLayerSettings,
    ): List<SlottedLayer> {
        val markers = FavouriteLayerFeatures.visible(library, settings)
        if (markers.isEmpty()) return emptyList()
        val data = FavouriteLayerFeatures.featureCollection(markers, settings)
        return buildList {
            add(shapeLayer(data))
            if (settings.iconsPerPoint) add(iconLayer(data))
            if (settings.showNames) add(nameLayer(data))
        }
    }

    private fun shapeLayer(data: String) =
        layer(
            id = SHAPE_LAYER_ID,
            data = data,
            layout =
                buildJsonObject {
                    put("icon-image", get("shapeIcon"))
                    put("icon-size", JsonPrimitive(1.05f))
                    put("icon-allow-overlap", JsonPrimitive(true))
                    put("icon-ignore-placement", JsonPrimitive(true))
                },
            paint =
                buildJsonObject {
                    put("icon-color", toColor("color"))
                },
        )

    private fun iconLayer(data: String) =
        layer(
            id = ICON_LAYER_ID,
            data = data,
            layout =
                buildJsonObject {
                    put("icon-image", get("iconSprite"))
                    put("icon-size", JsonPrimitive(0.5f))
                    put("icon-allow-overlap", JsonPrimitive(true))
                    put("icon-ignore-placement", JsonPrimitive(true))
                },
            paint =
                buildJsonObject {
                    put("icon-color", JsonPrimitive("#ffffff"))
                },
        )

    private fun nameLayer(data: String) =
        layer(
            id = NAME_LAYER_ID,
            data = data,
            layout =
                buildJsonObject {
                    put("text-field", get("name"))
                    put("text-font", JsonArray(listOf(JsonPrimitive("Noto Sans Medium"))))
                    put("text-size", JsonPrimitive(11f))
                    put("text-anchor", JsonPrimitive("top"))
                    put("text-offset", JsonArray(listOf(JsonPrimitive(0f), JsonPrimitive(1.4f))))
                    put("symbol-placement", JsonPrimitive("point"))
                },
            paint =
                buildJsonObject {
                    put("text-color", JsonPrimitive(ROUND_9_INK))
                    put("text-halo-color", JsonPrimitive(ROUND_9_MAP))
                    put("text-halo-width", JsonPrimitive(1.2f))
                },
        )

    private fun layer(
        id: String,
        data: String,
        layout: JsonObject,
        paint: JsonObject,
    ) = SlottedLayer(
        slot = LayerSlot.Markers,
        spec =
            LayerSpec(
                id = LayerId(id),
                type = LayerType.Symbol,
                data = data,
                minZoom = MIN_ZOOM,
                maxZoom = MAX_ZOOM,
                layout = layout,
                paint = paint,
            ),
    )

    private fun get(property: String) = JsonArray(listOf(JsonPrimitive("get"), JsonPrimitive(property)))

    private fun toColor(property: String) = JsonArray(listOf(JsonPrimitive("to-color"), get(property)))

    private const val ROUND_9_INK = "#e8eaed"
    private const val ROUND_9_MAP = "#1b1f24"
}
