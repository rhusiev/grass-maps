package nl.r1a.grassmap.plugins.way.alerts

import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * The way-alerts plugin (T-0319).
 *
 * The schedule is compiled-in behaviour like the beeps: `app` builds
 * [WayAlertHost] over the host's [nl.r1a.grassmap.plugin.api.host.RegionAccess]
 * and the live navigation state, and bridges each cue into the alert host's
 * typed banner and effect path. The plugin therefore declares no command,
 * stream or provider, and activation is a no-op. The manifest exists because
 * every module under `plugins/` carries one, and it names the region data the
 * feature needs.
 */
class WayAlertsPlugin : Plugin {
    override suspend fun activate(context: PluginContext): Outcome<Unit> = Outcome.Success(Unit)
}
