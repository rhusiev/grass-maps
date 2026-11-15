package nl.r1a.grassmap.plugins.raster

import nl.r1a.grassmap.plugin.api.model.SlottedLayer
import nl.r1a.grassmap.plugin.api.model.StyleId
import nl.r1a.grassmap.plugin.api.model.StyleSpec
import nl.r1a.grassmap.plugin.api.provider.MapLayerContract
import nl.r1a.grassmap.plugin.api.provider.MapLayerProvider
import nl.r1a.grassmap.plugin.api.provider.ProviderContract

/**
 * The online tile overlay (T-0512).
 *
 * The provider supplies no base style: it draws one raster layer over the
 * active Standard or Touring style. The app shows a source through [show] and
 * removes it through [show] with null, so the layer belongs to the plugin and
 * the map host owns the drawing.
 *
 * [allows] is the synchronous check the map engine's request interceptor uses;
 * it answers only whether [url] belongs to the shown source, never whether the
 * network is permitted. The gate decides that separately for every tile.
 */
class RasterLayerProvider : MapLayerProvider {
    @Volatile
    private var source: RasterSource? = null

    override val contract: ProviderContract = MapLayerContract.REFERENCE

    fun show(source: RasterSource?) {
        this.source = source
    }

    fun activeSource(): RasterSource? = source

    fun allows(url: String): Boolean = source?.allows(url) == true

    override fun styles(): List<StyleSpec> = emptyList()

    override fun layers(styleId: StyleId): List<SlottedLayer> = source?.let { active -> listOf(RasterLayers.layer(active)) } ?: emptyList()
}
