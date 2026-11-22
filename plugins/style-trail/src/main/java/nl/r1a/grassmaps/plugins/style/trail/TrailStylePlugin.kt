package nl.r1a.grassmaps.plugins.style.trail

import nl.r1a.grassmaps.plugin.api.contract.Plugin
import nl.r1a.grassmaps.plugin.api.contract.PluginContext
import nl.r1a.grassmaps.plugin.api.error.Outcome

/**
 * The first-party Trail map style plugin: one `MapLayerProvider`.
 */
class TrailStylePlugin : Plugin {
    private val provider = TrailMapLayerProvider()

    override suspend fun activate(context: PluginContext): Outcome<Unit> {
        context.addProvider(provider)
        return Outcome.Success(Unit)
    }
}
