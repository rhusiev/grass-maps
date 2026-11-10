package nl.r1a.grassmap.plugins.direct

import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * The direct routing plugin: a second `routing.engine` provider (T-0305).
 *
 * It registers one engine and nothing else. [DirectRoutingEngine] needs no
 * host service - no pack directory, no elevation, no network - so activation
 * cannot fail on missing data and the provider is usable the moment it is
 * resolved.
 */
class DirectPlugin : Plugin {
    override suspend fun activate(context: PluginContext): Outcome<Unit> {
        context.addProvider(DirectRoutingEngine())
        return Outcome.Success(Unit)
    }
}
