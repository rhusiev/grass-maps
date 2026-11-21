package nl.r1a.grassmaps.plugins.favourites.layer

import nl.r1a.grassmaps.plugin.api.contract.Plugin
import nl.r1a.grassmaps.plugin.api.contract.PluginContext
import nl.r1a.grassmaps.plugin.api.error.Outcome

/**
 * The saved-places layer plugin (T-0215).
 *
 * Like `track.layer` the layer is compiled-in behaviour: `app` builds
 * [FavouritesLayerController] over the favourites store's snapshot and the
 * bound map host. The plugin contributes no command, stream or provider, so
 * activation is a no-op. Its manifest exists because every module under
 * `plugins/` carries one.
 */
class FavouritesLayerPlugin : Plugin {
    override suspend fun activate(context: PluginContext): Outcome<Unit> = Outcome.Success(Unit)
}
