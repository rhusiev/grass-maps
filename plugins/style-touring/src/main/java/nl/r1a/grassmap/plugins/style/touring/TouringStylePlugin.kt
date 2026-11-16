package nl.r1a.grassmap.plugins.style.touring

import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * The first-party touring map style plugin: one `MapLayerProvider`.
 */
class TouringStylePlugin : Plugin {
    private val provider = TouringMapLayerProvider()

    override suspend fun activate(context: PluginContext): Outcome<Unit> {
        context.addProvider(provider)
        return Outcome.Success(Unit)
    }
}
