package nl.r1a.grassmap.plugins.route.layer

import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * The route-layer plugin (T-0408).
 *
 * The layer is compiled-in behaviour like the track layer: `app` builds
 * [RouteLayer] over the bound map host and the map screen hands it the route to
 * draw. The plugin therefore declares no command, stream or provider, and
 * activation is a no-op. The manifest exists because every module under
 * `plugins/` carries one.
 */
class RouteLayerPlugin : Plugin {
    override suspend fun activate(context: PluginContext): Outcome<Unit> = Outcome.Success(Unit)
}
