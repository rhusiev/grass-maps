package nl.r1a.grassmaps.plugins.favourites.layer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import nl.r1a.grassmaps.plugin.api.contract.PluginId
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.error.onFailure
import nl.r1a.grassmaps.plugin.api.host.MapHost
import nl.r1a.grassmaps.plugin.api.model.LayerSlot

/**
 * Keeps the saved-place layers on the bound map.
 *
 * The host supplies the library reader, a signal flow for store mutations and
 * the persisted control state. Nothing here runs per camera frame: a redraw
 * happens on a library signal or a control change, and replaces this owner's
 * layers as a whole, so no stale marker survives an edit.
 */
class FavouritesLayerController(
    private val library: suspend () -> Outcome<FavouriteLayerLibrary>,
    private val changes: Flow<Unit>,
    private val settings: StateFlow<FavouriteLayerSettings>,
    private val host: () -> MapHost?,
    private val log: (String) -> Unit = {},
    private val owner: PluginId = OWNER,
) {
    /** Draws the current library once, for a newly bound surface. */
    suspend fun refresh(): Outcome<Unit> {
        val map = host() ?: return Outcome.Failure(AppError.NotReady)
        val snapshot =
            when (val loaded = library()) {
                is Outcome.Failure -> {
                    map.removeLayers(owner)
                    log("saved places unreadable: ${loaded.error}")
                    return loaded
                }

                is Outcome.Success -> {
                    loaded.value
                }
            }
        val layers = FavouriteLayerLayers.build(snapshot, settings.value)
        map.removeLayers(owner).onFailure { error -> log("saved place layers not cleared: $error") }
        for (layer in layers) {
            when (val added = map.addLayer(LayerSlot.Markers, layer.spec, owner)) {
                is Outcome.Failure -> {
                    map.removeLayers(owner).onFailure { error -> log("saved place layers not cleared: $error") }
                    log("saved place layer ${layer.spec.id} rejected: ${added.error}")
                    return added
                }

                is Outcome.Success -> {
                    Unit
                }
            }
        }
        return Outcome.Success(Unit)
    }

    /** Redraws on each library signal and each control change until cancelled. */
    suspend fun run() {
        refresh().onFailure { error -> log("saved places not drawn: $error") }
        merge(changes, settings.drop(1).map { Unit }).collect {
            refresh().onFailure { error -> log("saved places not redrawn: $error") }
        }
    }

    companion object {
        val OWNER: PluginId = PluginId("favourites.layer")
    }
}
