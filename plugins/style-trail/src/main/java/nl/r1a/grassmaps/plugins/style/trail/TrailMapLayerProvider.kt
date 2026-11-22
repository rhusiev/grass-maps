package nl.r1a.grassmaps.plugins.style.trail

import nl.r1a.grassmaps.plugin.api.model.PaletteStyleDocument
import nl.r1a.grassmaps.plugin.api.model.SlottedLayer
import nl.r1a.grassmaps.plugin.api.model.StyleId
import nl.r1a.grassmaps.plugin.api.model.StyleSpec
import nl.r1a.grassmaps.plugin.api.provider.MapLayerContract
import nl.r1a.grassmaps.plugin.api.provider.MapLayerProvider
import nl.r1a.grassmaps.plugin.api.provider.ProviderContract
import nl.r1a.grassmaps.plugins.style.trail.generated.TrailStyleAsset

/**
 * Supplies the Trail base style and its layers in a light and a dark variant.
 * Its base document equals the Meadow one, so switching between the two
 * styles updates layers in place.
 */
internal class TrailMapLayerProvider : MapLayerProvider {
    override val contract: ProviderContract = MapLayerContract.REFERENCE

    private val style: PaletteStyleDocument by lazy {
        PaletteStyleDocument.parse(TrailStyleAsset.TEXT)
    }

    override fun styles(): List<StyleSpec> =
        style.variants().map { id ->
            StyleSpec(
                id = id,
                baseStyleJson = style.baseStyle(),
                sources = PaletteStyleDocument.sources,
            )
        }

    override fun layers(styleId: StyleId): List<SlottedLayer> = style.layersFor(styleId)
}
