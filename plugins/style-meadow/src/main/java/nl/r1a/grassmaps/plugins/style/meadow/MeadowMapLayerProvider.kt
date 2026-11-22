package nl.r1a.grassmaps.plugins.style.meadow

import nl.r1a.grassmaps.plugin.api.model.PaletteStyleDocument
import nl.r1a.grassmaps.plugin.api.model.SlottedLayer
import nl.r1a.grassmaps.plugin.api.model.StyleId
import nl.r1a.grassmaps.plugin.api.model.StyleSpec
import nl.r1a.grassmaps.plugin.api.provider.MapLayerContract
import nl.r1a.grassmaps.plugin.api.provider.MapLayerProvider
import nl.r1a.grassmaps.plugin.api.provider.ProviderContract
import nl.r1a.grassmaps.plugins.style.meadow.generated.MeadowStyleAsset

/**
 * Supplies the Meadow base style and its layers in a light and a dark variant.
 * The Trail style (`plugins/style-trail`) has the same base document, so
 * switching between them updates layers in place.
 */
internal class MeadowMapLayerProvider : MapLayerProvider {
    override val contract: ProviderContract = MapLayerContract.REFERENCE

    private val style: PaletteStyleDocument by lazy { PaletteStyleDocument.parse(MeadowStyleAsset.TEXT) }

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
