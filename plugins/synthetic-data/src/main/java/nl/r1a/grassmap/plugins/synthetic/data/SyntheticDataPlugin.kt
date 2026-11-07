package nl.r1a.grassmap.plugins.synthetic.data

import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * The first-party synthetic DataSource plugin. It proves the `data.source`
 * contract with authored attributes and one real contour layer, and depends on
 * `:plugin-api` alone.
 */
class SyntheticDataPlugin : Plugin {
    private val provider = SyntheticElevationDataSource()

    override suspend fun activate(context: PluginContext): Outcome<Unit> {
        context.addProvider(provider)
        return Outcome.Success(Unit)
    }
}
