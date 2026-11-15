package nl.r1a.grassmap.plugins.raster

import nl.r1a.grassmap.plugin.api.model.SlottedLayer
import nl.r1a.grassmap.plugin.api.model.StyleId
import nl.r1a.grassmap.plugin.api.model.StyleSpec
import nl.r1a.grassmap.plugin.api.provider.MapLayerContract
import nl.r1a.grassmap.plugin.api.provider.MapLayerProvider
import nl.r1a.grassmap.plugin.api.provider.ProviderContract

/**
 * The online tile layer (T-0512), with its placement and transparency
 * (T-0514).
 *
 * The provider supplies no base style: it draws one raster layer over the
 * active Standard or Touring style. The app shows a source through [show] and
 * removes it through [show] with null, so the layer belongs to the plugin and
 * the map host owns the drawing. A changed placement or opacity is the same
 * layer with a different slot or `raster-opacity`, so nothing is removed.
 *
 * [allows] is the synchronous check the map engine's request interceptor uses;
 * it answers only whether [url] belongs to the shown source, never whether the
 * network is permitted. The gate decides that separately for every tile.
 */
class RasterLayerProvider : MapLayerProvider {
    private data class Shown(
        val source: RasterSource,
        val placement: RasterPlacement,
        val opacity: Float,
    )

    @Volatile
    private var shown: Shown? = null

    override val contract: ProviderContract = MapLayerContract.REFERENCE

    fun show(
        source: RasterSource?,
        placement: RasterPlacement = RasterPlacement.Overlay,
        opacity: Float = RasterTransparency.FULL_OPACITY,
    ) {
        shown = source?.let { Shown(it, placement, opacity) }
    }

    fun activeSource(): RasterSource? = shown?.source

    fun allows(url: String): Boolean = activeSource()?.allows(url) == true

    override fun styles(): List<StyleSpec> = emptyList()

    override fun layers(styleId: StyleId): List<SlottedLayer> =
        shown?.let { active ->
            listOf(RasterLayers.layer(active.source, active.placement, active.opacity))
        } ?: emptyList()
}
