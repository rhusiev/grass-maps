package nl.r1a.grassmaps.plugins.online.search

import nl.r1a.grassmaps.plugin.api.contract.Plugin
import nl.r1a.grassmaps.plugin.api.contract.PluginContext
import nl.r1a.grassmaps.plugin.api.error.Outcome

/**
 * The online-search plugin: one `places.search` provider over Photon, behind
 * the host's network gate. The manifest names `network.online_search`, so the
 * host shows this plugin as opting into that contact.
 */
class OnlineSearchPlugin : Plugin {
    override suspend fun activate(context: PluginContext): Outcome<Unit> {
        context.addProvider(PhotonPlaceProvider(gate = context.services.network))
        return Outcome.Success(Unit)
    }
}
