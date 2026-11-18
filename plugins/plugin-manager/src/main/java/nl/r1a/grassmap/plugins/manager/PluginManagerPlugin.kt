package nl.r1a.grassmap.plugins.manager

import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * The plugin manager (T-0607).
 *
 * It contributes nothing to the host. The screen is a pure function of the
 * `core.plugins.state` list and two callbacks, so `app` hosts
 * [PluginManagerScreen] and routes the switch to `core.plugins.set_enabled`.
 */
class PluginManagerPlugin : Plugin {
    override suspend fun activate(context: PluginContext): Outcome<Unit> = Outcome.Success(Unit)
}
