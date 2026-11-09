package nl.r1a.grassmap.plugins.osm.places

import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * The OSM place-card plugin: one `places.card` provider and no command or UI,
 * so it depends only on `:plugin-api`. The host hands the provider the details
 * the `places.search` contract returned.
 */
class OsmPlacesPlugin : Plugin {
    override suspend fun activate(context: PluginContext): Outcome<Unit> {
        context.addProvider(OsmPlaceCard())
        return Outcome.Success(Unit)
    }
}
