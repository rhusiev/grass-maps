package nl.r1a.grassmap.plugins.track.layer

import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * The track-layer plugin (T-0401).
 *
 * The layer is compiled-in behaviour like the way alerts: `app` builds
 * [TrackLayer] over the track store's drawable list and the bound map host, and
 * the controller writes the library through `MapHost.setTracks`. The plugin
 * therefore declares no command, stream or provider, and activation is a no-op.
 * The manifest exists because every module under `plugins/` carries one.
 */
class TrackLayerPlugin : Plugin {
    override suspend fun activate(context: PluginContext): Outcome<Unit> = Outcome.Success(Unit)
}
