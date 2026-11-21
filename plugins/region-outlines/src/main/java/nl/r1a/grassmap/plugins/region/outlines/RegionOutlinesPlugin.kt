package nl.r1a.grassmaps.plugins.region.outlines

import nl.r1a.grassmaps.plugin.api.contract.Plugin
import nl.r1a.grassmaps.plugin.api.contract.PluginContext
import nl.r1a.grassmaps.plugin.api.error.Outcome

/**
 * The region-outlines plugin (T-0506).
 *
 * Like the track layer it is compiled-in behaviour: `app` builds
 * [RegionOutlinesLayer] over the downloads data and the bound map host. The
 * plugin declares no command, stream or provider, and activation is a no-op.
 */
class RegionOutlinesPlugin : Plugin {
    override suspend fun activate(context: PluginContext): Outcome<Unit> = Outcome.Success(Unit)
}
