package nl.r1a.grassmap.plugins.valhalla

import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * The Valhalla routing plugin: one `routing.engine` provider (T-0301).
 *
 * The engine is built here, not by the host, and reads the pack directory from
 * [nl.r1a.grassmap.plugin.api.host.PluginServices.routingPacks] when the first
 * route needs it. Activation therefore succeeds before a pack is mounted, and
 * a route before one is a `NotFound` value rather than a failed plugin.
 */
class ValhallaPlugin : Plugin {
    override suspend fun activate(context: PluginContext): Outcome<Unit> {
        context.addProvider(
            ValhallaRoutingEngine(
                packs = context.services.routingPacks,
                elevation = context.services.elevation,
            ),
        )
        return Outcome.Success(Unit)
    }
}
