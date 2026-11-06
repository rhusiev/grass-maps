package nl.r1a.grassmap.plugin.api.provider

import nl.r1a.grassmap.plugin.api.model.SlottedLayer
import nl.r1a.grassmap.plugin.api.model.StyleId
import nl.r1a.grassmap.plugin.api.model.StyleSpec

/**
 * The map style contract. A style plugin supplies one or more base styles and
 * their layers.
 */
interface MapLayerProvider : Provider {
    /** Every base style this provider can load. */
    fun styles(): List<StyleSpec>

    /** The layers of [styleId], in draw order inside each slot. */
    fun layers(styleId: StyleId): List<SlottedLayer>
}

object MapLayerContract {
    const val NAME: String = "map.layers"
    const val VERSION: String = "1.0.0"

    val REFERENCE: ProviderContract = ProviderContract(NAME, VERSION)
}
