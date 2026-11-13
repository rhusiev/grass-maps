package nl.r1a.grassmap.plugins.route.warnings

import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * The route-warnings plugin (T-0409).
 *
 * The warning list is compiled-in behaviour: `app` builds [RouteWarningScanner]
 * over the mounted [nl.r1a.grassmap.plugin.api.host.RegionAccess] and asks it
 * for a route and the active profile. The plugin declares no command, stream
 * or provider, and activation is a no-op. Its manifest exists because every
 * module under `plugins/` carries one, and it names the region component the
 * feature reads. The row and the list that show the scanner's answer are
 * `ui-shell`'s, wired in `app`.
 */
class RouteWarningsPlugin : Plugin {
    override suspend fun activate(context: PluginContext): Outcome<Unit> = Outcome.Success(Unit)
}
