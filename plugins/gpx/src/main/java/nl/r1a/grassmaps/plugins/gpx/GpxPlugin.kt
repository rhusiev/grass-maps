package nl.r1a.grassmaps.plugins.gpx

import nl.r1a.grassmaps.plugin.api.contract.Plugin
import nl.r1a.grassmaps.plugin.api.contract.PluginContext
import nl.r1a.grassmaps.plugin.api.error.Outcome

/**
 * The GPX file format plugin. It contributes one provider and no command or UI,
 * so it depends only on `:plugin-api`.
 */
class GpxPlugin : Plugin {
    override suspend fun activate(context: PluginContext): Outcome<Unit> {
        context.addProvider(GpxFormat())
        return Outcome.Success(Unit)
    }
}
