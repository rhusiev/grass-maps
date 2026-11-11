package nl.r1a.grassmap.plugins.guidance.ui

import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * The guidance panel plugin (T-0310).
 *
 * The panel is compiled-in chrome: [GuidancePanelContent] draws the model the
 * app derives from the navigation state it already holds. The plugin declares
 * no command, stream or provider because the guidance state stays in
 * `navigation`; the app maps it onto [GuidanceInput] and never lets the panel
 * read the follower. Activation is therefore a no-op, and the manifest exists
 * because every module under `plugins/` carries one.
 */
class GuidanceUiPlugin : Plugin {
    override suspend fun activate(context: PluginContext): Outcome<Unit> = Outcome.Success(Unit)
}
