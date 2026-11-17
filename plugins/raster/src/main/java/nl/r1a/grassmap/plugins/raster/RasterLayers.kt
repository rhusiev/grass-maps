package nl.r1a.grassmap.plugins.raster

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.model.LayerId
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerSpec
import nl.r1a.grassmap.plugin.api.model.LayerType
import nl.r1a.grassmap.plugin.api.model.RasterSourceSpec
import nl.r1a.grassmap.plugin.api.model.SlottedLayer

/**
 * Where the online tile layer draws in the style's fixed z-order groups
 * (T-0514).
 *
 * Both placements stay behind the privacy gate: the placement changes the
 * slot, never the tile path.
 */
enum class RasterPlacement {
    /**
     * Below every base-style layer. The base map draws over the tiles, so this
     * placement needs a style whose background is not opaque to be visible.
     */
    Underlay,

    /**
     * Above every base-style layer, and below the contour, track, route,
     * marker and label groups.
     */
    Overlay,
}

/**
 * The transparency the user sets on the tile layer, held as whole percent.
 *
 * Percent is the unit the control speaks and the stored value, so 40 % is
 * exactly `0.4f` when it reaches the style property. The style property itself
 * is `raster-opacity`, as MapLibre names it.
 */
object RasterTransparency {
    const val MIN_PERCENT = 0
    const val MAX_PERCENT = 100
    const val RASTER_OPACITY_PROPERTY = "raster-opacity"

    /** The style value that draws the tiles with nothing of the map showing through. */
    const val FULL_OPACITY = 1f

    /** The `raster-opacity` value for [percent], clamped to the control's range. */
    fun opacityOf(percent: Int): Float = percent.coerceIn(MIN_PERCENT, MAX_PERCENT) / PERCENT_SCALE

    private const val PERCENT_SCALE = 100f
}

/** One installed offline area as the layer needs it: the pack file and its zoom range. */
data class RasterAreaLayer(
    val id: String,
    val packFile: String,
    val minZoom: Int,
    val maxZoom: Int,
    val attributionHtml: String,
    val tileSize: Int = DEFAULT_TILE_SIZE,
) {
    private companion object {
        const val DEFAULT_TILE_SIZE = 256
    }
}

/** The style layer an online tile source draws at its placement. */
object RasterLayers {
    val LAYER_ID: LayerId = LayerId("map.raster.online")

    /**
     * The layer that draws [source] at [placement] and [opacity].
     *
     * The source keeps its own zoom range: that range says which tile zooms
     * exist, and MapLibre overzooms the top ones. The layer keeps the map's
     * whole zoom range instead, because MapLibre hides a layer at or above the
     * layer's `maxzoom` - inheriting the source's top tile zoom would blank the
     * tiles above it rather than scale them up (found on the 13.0 capture of a
     * source that serves 12 and 13: the layer was hidden at exactly its own
     * `maxzoom` and no tile was ever requested).
     */
    fun layer(
        source: RasterSource,
        placement: RasterPlacement = RasterPlacement.Overlay,
        opacity: Float = RasterTransparency.FULL_OPACITY,
    ): SlottedLayer =
        SlottedLayer(
            slot = slotOf(placement),
            spec =
                LayerSpec(
                    id = LAYER_ID,
                    type = LayerType.Raster,
                    raster =
                        RasterSourceSpec(
                            tiles = listOf(source.tileUrlTemplate),
                            tileSize = source.tileSize,
                            minZoom = source.minZoom,
                            maxZoom = source.maxZoom,
                            attributionHtml = source.attributionHtml.ifBlank { null },
                        ),
                    paint = paintOf(opacity),
                ),
        )

    /**
     * The layer that draws one installed offline area from its pack file
     * (T-0513). It opens no socket: the engine reads the file, so the online
     * tiles switch does not apply and the layer works with the network off.
     */
    fun areaLayer(
        area: RasterAreaLayer,
        placement: RasterPlacement = RasterPlacement.Overlay,
        opacity: Float = RasterTransparency.FULL_OPACITY,
    ): SlottedLayer =
        SlottedLayer(
            slot = slotOf(placement),
            spec =
                LayerSpec(
                    id = LayerId("$AREA_LAYER_PREFIX${area.id}"),
                    type = LayerType.Raster,
                    raster =
                        RasterSourceSpec(
                            tileSize = area.tileSize,
                            minZoom = area.minZoom,
                            maxZoom = area.maxZoom,
                            attributionHtml = area.attributionHtml.ifBlank { null },
                            packFile = area.packFile,
                        ),
                    paint = paintOf(opacity),
                ),
        )

    private const val AREA_LAYER_PREFIX = "map.raster.area."

    fun slotOf(placement: RasterPlacement): LayerSlot =
        when (placement) {
            RasterPlacement.Underlay -> LayerSlot.Underlay
            RasterPlacement.Overlay -> LayerSlot.Terrain
        }

    private fun paintOf(opacity: Float): JsonObject =
        buildJsonObject {
            put(
                RasterTransparency.RASTER_OPACITY_PROPERTY,
                opacity.coerceIn(0f, RasterTransparency.FULL_OPACITY),
            )
        }
}
