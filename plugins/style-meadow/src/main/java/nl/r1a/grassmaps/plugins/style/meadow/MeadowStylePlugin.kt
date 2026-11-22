package nl.r1a.grassmaps.plugins.style.meadow

import nl.r1a.grassmaps.plugin.api.contract.Plugin
import nl.r1a.grassmaps.plugin.api.contract.PluginContext
import nl.r1a.grassmaps.plugin.api.error.Outcome

/**
 * The first-party Meadow map style plugin: one `MapLayerProvider`.
 */
class MeadowStylePlugin : Plugin {
    private val provider = MeadowMapLayerProvider()

    override suspend fun activate(context: PluginContext): Outcome<Unit> {
        context.addProvider(provider)
        return Outcome.Success(Unit)
    }
}
