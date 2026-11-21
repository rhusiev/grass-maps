package nl.r1a.grassmaps.plugins.layers.panel

import nl.r1a.grassmaps.plugin.api.contract.Plugin
import nl.r1a.grassmaps.plugin.api.contract.PluginContext
import nl.r1a.grassmaps.plugin.api.error.Outcome

/**
 * The layers panel plugin (T-0109): the Layers button and the card that
 * switches the map style. It contributes no provider and no command. The app
 * owns the active style and the other layer cards, and this module supplies the
 * chrome and the pure selection state.
 */
class LayersPanelPlugin : Plugin {
    override suspend fun activate(context: PluginContext): Outcome<Unit> = Outcome.Success(Unit)
}
