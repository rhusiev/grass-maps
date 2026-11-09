package nl.r1a.grassmap.plugins.links

import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * The links plugin (T-0211).
 *
 * It owns the pure parsers behind "open this link in the maps app": the
 * `geo:` form, osm.org element and permalink URLs, and the osm.org short
 * links. It contributes no command, because opening a link is an Activity
 * intent from another app, not something the user or the control channel
 * invokes. The app routes the intent and owns the camera, so this module stays
 * on `:plugin-api`.
 */
class LinksPlugin : Plugin {
    override suspend fun activate(context: PluginContext): Outcome<Unit> = Outcome.Success(Unit)
}
