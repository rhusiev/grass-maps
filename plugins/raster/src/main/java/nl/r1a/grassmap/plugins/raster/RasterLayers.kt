package nl.r1a.grassmap.plugins.raster

import nl.r1a.grassmap.plugin.api.model.LayerId
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerSpec
import nl.r1a.grassmap.plugin.api.model.LayerType
import nl.r1a.grassmap.plugin.api.model.RasterSourceSpec
import nl.r1a.grassmap.plugin.api.model.SlottedLayer

/** The style layer an online tile source draws in the base slot. */
object RasterLayers {
    val LAYER_ID: LayerId = LayerId("map.raster.online")

    fun layer(source: RasterSource): SlottedLayer =
        SlottedLayer(
            slot = LayerSlot.Base,
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
                ),
        )
}
