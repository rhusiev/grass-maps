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
 * Both placements draw below the map's own line and symbol groups, and both
 * stay behind the privacy gate: the placement changes the slot, never the tile
 * path.
 */
enum class RasterPlacement {
    /** Below every base layer, so the base map's fills draw over the tiles. */
    Underlay,

    /** Above the base fills and below contours, tracks, routes, markers and labels. */
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

/** The style layer an online tile source draws at its placement. */
object RasterLayers {
    val LAYER_ID: LayerId = LayerId("map.raster.online")

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
                    minZoom = source.minZoom.toFloat(),
                    maxZoom = source.maxZoom.toFloat(),
                    paint = paintOf(opacity),
                ),
        )

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
