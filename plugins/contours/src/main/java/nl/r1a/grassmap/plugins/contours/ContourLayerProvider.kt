package nl.r1a.grassmap.plugins.contours

import nl.r1a.grassmap.plugin.api.model.SlottedLayer
import nl.r1a.grassmap.plugin.api.model.StyleId
import nl.r1a.grassmap.plugin.api.model.StyleSpec
import nl.r1a.grassmap.plugin.api.provider.MapLayerContract
import nl.r1a.grassmap.plugin.api.provider.MapLayerProvider
import nl.r1a.grassmap.plugin.api.provider.ProviderContract

/**
 * Supplies the contour overlays for the Standard and Touring variants.
 *
 * The plugin is an overlay provider, not a base-style provider: it publishes no
 * [StyleSpec] of its own and draws on the `contours` source that the mounted
 * region resolves (data-formats.md section 3). The host asks for the layers of
 * the active style id, so a variant switch swaps the palette and zoom table
 * without a style reload.
 */
internal class ContourLayerProvider : MapLayerProvider {
    override val contract: ProviderContract = MapLayerContract.REFERENCE

    override fun styles(): List<StyleSpec> = emptyList()

    override fun layers(styleId: StyleId): List<SlottedLayer> = ContourLayers.layers(styleId)
}
