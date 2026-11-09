package nl.r1a.grassmap.plugins.share

import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * The share plugin (T-0210).
 *
 * It turns a point, a place and the current location into the text, the
 * osm.org link and the `geo:` URI a share carries, and hands the effect to the
 * host's [nl.r1a.grassmap.plugin.api.host.PlatformActions]. The plugin never
 * touches a `ClipboardManager` or an `Intent`, so it depends only on
 * `:plugin-api`. The card's coordinate rows and buttons come from [ShareCard]
 * and are composed by `app`.
 */
class SharePlugin : Plugin {
    override suspend fun activate(context: PluginContext): Outcome<Unit> {
        val actions = context.services.platformActions
        context.addCommand(CopyCoordinatesCommand(actions))
        context.addCommand(SharePlaceCommand(actions))
        context.addCommand(ShareLocationCommand(actions, context.services))
        context.addCommand(OpenPointCommand(actions))
        return Outcome.Success(Unit)
    }
}
