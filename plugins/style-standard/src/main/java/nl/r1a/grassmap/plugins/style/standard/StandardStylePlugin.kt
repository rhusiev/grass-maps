package nl.r1a.grassmap.plugins.style.standard

import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * The first-party standard map style plugin: one `MapLayerProvider`.
 */
class StandardStylePlugin : Plugin {
    private val provider = StandardMapLayerProvider()

    override suspend fun activate(context: PluginContext): Outcome<Unit> {
        context.addProvider(provider)
        return Outcome.Success(Unit)
    }
}
