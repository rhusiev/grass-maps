package nl.r1a.grassmap.plugins.raster

import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * The online raster tile plugin (T-0512).
 *
 * One `map.layers` provider draws the chosen tile source. The manifest names
 * `network.online_tiles`, so the privacy settings can say that enabling it
 * shows the tile server which area is on screen. The sources themselves are
 * presets plus a user URL template; no source is a second network path.
 */
class RasterPlugin : Plugin {
    private val provider = RasterLayerProvider()

    override suspend fun activate(context: PluginContext): Outcome<Unit> {
        context.addProvider(provider)
        return Outcome.Success(Unit)
    }
}
