package nl.r1a.grassmap.plugins.style.standard

import nl.r1a.grassmap.plugin.api.model.SlottedLayer
import nl.r1a.grassmap.plugin.api.model.StyleId
import nl.r1a.grassmap.plugin.api.model.StyleSpec
import nl.r1a.grassmap.plugin.api.provider.MapLayerContract
import nl.r1a.grassmap.plugin.api.provider.MapLayerProvider
import nl.r1a.grassmap.plugin.api.provider.ProviderContract
import nl.r1a.grassmap.plugins.style.standard.generated.StandardStyleAsset

/**
 * Supplies the Standard and Touring base style and its layers. Both variants
 * share one base document, so switching between them updates layers in place.
 */
internal class StandardMapLayerProvider : MapLayerProvider {
    override val contract: ProviderContract = MapLayerContract.REFERENCE

    private val style: StandardStyle by lazy { StandardStyle.parse(StandardStyleAsset.TEXT) }

    override fun styles(): List<StyleSpec> =
        style.variants().map { id ->
            StyleSpec(
                id = id,
                baseStyleJson = style.baseStyle(),
                sources = StandardStyle.sources,
            )
        }

    override fun layers(styleId: StyleId): List<SlottedLayer> = style.layersFor(styleId)
}
