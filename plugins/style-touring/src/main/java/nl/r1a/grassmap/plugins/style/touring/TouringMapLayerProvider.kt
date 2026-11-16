package nl.r1a.grassmap.plugins.style.touring

import nl.r1a.grassmap.plugin.api.model.PaletteStyleDocument
import nl.r1a.grassmap.plugin.api.model.SlottedLayer
import nl.r1a.grassmap.plugin.api.model.StyleId
import nl.r1a.grassmap.plugin.api.model.StyleSpec
import nl.r1a.grassmap.plugin.api.provider.MapLayerContract
import nl.r1a.grassmap.plugin.api.provider.MapLayerProvider
import nl.r1a.grassmap.plugin.api.provider.ProviderContract
import nl.r1a.grassmap.plugins.style.touring.generated.TouringStyleAsset

/**
 * Supplies the Touring base style and its layers in a light and a dark variant.
 * Its base document equals the Standard one, so switching between the two
 * styles updates layers in place.
 */
internal class TouringMapLayerProvider : MapLayerProvider {
    override val contract: ProviderContract = MapLayerContract.REFERENCE

    private val style: PaletteStyleDocument by lazy {
        PaletteStyleDocument.parse(TouringStyleAsset.TEXT)
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
