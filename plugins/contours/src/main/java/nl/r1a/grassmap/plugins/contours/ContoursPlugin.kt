package nl.r1a.grassmap.plugins.contours

import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * The contour plugin (T-0417).
 *
 * The manifest declares the `contours` region component and one `map.layers`
 * provider. The provider adds the overlay layers to the active style; the host
 * mounts the component and owns the on/off state, because a layer toggle is a
 * user setting, not region data.
 */
class ContoursPlugin : Plugin {
    private val provider = ContourLayerProvider()

    override suspend fun activate(context: PluginContext): Outcome<Unit> {
        context.addProvider(provider)
        return Outcome.Success(Unit)
    }
}
