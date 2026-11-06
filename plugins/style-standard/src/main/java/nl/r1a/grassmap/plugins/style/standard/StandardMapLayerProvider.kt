package nl.r1a.grassmap.plugins.style.standard

import nl.r1a.grassmap.plugin.api.model.SlottedLayer
import nl.r1a.grassmap.plugin.api.model.StyleId
import nl.r1a.grassmap.plugin.api.model.StyleSpec
import nl.r1a.grassmap.plugin.api.provider.MapLayerContract
import nl.r1a.grassmap.plugin.api.provider.MapLayerProvider
import nl.r1a.grassmap.plugin.api.provider.ProviderContract
import nl.r1a.grassmap.plugins.style.standard.generated.BenchStyleAsset

/**
 * Supplies the Standard and Touring base style and its layers. Both variants
 * share one base document, so switching between them updates layers in place.
 */
internal class StandardMapLayerProvider : MapLayerProvider {
    override val contract: ProviderContract = MapLayerContract.REFERENCE

    private val style: BenchStyle by lazy { BenchStyle.parse(BenchStyleAsset.TEXT) }

    override fun styles(): List<StyleSpec> =
        StyleVariant.entries.map { variant ->
            StyleSpec(
                id = variant.id,
                baseStyleJson = style.baseStyle(),
                sources = BenchStyle.sources,
            )
        }

    override fun layers(styleId: StyleId): List<SlottedLayer> {
        val variant = StyleVariant.entries.firstOrNull { it.id == styleId } ?: return emptyList()
        return style.layersFor(variant)
    }
}
