package nl.r1a.grassmap.plugins.route.relations

import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * The hiking and cycling route relations plugin (T-0418, T-0419).
 *
 * Like the way-alert schedule, the drawing is app-owned wiring: `app` builds
 * [RouteRelationsMapController] over the mounted [nl.r1a.grassmap.plugin.api.host.RegionAccess]
 * and the bound [nl.r1a.grassmap.plugin.api.host.MapHost], and the controller
 * adds the band and waymark layers. The plugin therefore declares no command,
 * stream or provider; its manifest names the region component the feature
 * reads and activation is a no-op.
 */
class RouteRelationsPlugin : Plugin {
    override suspend fun activate(context: PluginContext): Outcome<Unit> = Outcome.Success(Unit)
}
