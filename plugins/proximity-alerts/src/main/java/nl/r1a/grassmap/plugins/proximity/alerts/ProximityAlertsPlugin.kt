package nl.r1a.grassmap.plugins.proximity.alerts

import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * The proximity-alerts plugin (T-0413).
 *
 * The schedule is compiled-in behaviour like the way alerts: `app` builds
 * [ProximityAlertHost] over the favourites library, the track store and the
 * search index, and bridges each cue into the T-0317 alert host, so every
 * category keeps its own off-by-default switch. The plugin therefore declares
 * no command, stream or provider, and activation is a no-op. The manifest
 * exists because every module under `plugins/` carries one, and it says the
 * feature reads no network and no location of its own: the navigation session
 * already has the fix.
 */
class ProximityAlertsPlugin : Plugin {
    override suspend fun activate(context: PluginContext): Outcome<Unit> = Outcome.Success(Unit)
}
