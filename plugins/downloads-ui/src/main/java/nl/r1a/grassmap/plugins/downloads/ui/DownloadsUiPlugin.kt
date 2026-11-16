package nl.r1a.grassmap.plugins.downloads.ui

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.StreamDescriptor
import nl.r1a.grassmap.plugin.api.model.DownloadsState

/**
 * The first-party downloads plugin (T-0504).
 *
 * It owns the downloads screen state, its actions and its stream, and reads
 * everything it draws from the host's
 * [nl.r1a.grassmap.plugin.api.host.RegionDownloadsAccess]. The store owns the
 * catalogue, the packages and the transfers; this plugin owns no second copy of
 * any of them, so the map's installed packs and the screen cannot disagree.
 *
 * Activation fetches the catalogue, which is what "when the user opens the
 * downloads screen" means in server-protocol.md section 1. A failure is a typed
 * problem in the published state, not a failed activation: the screen still
 * opens and offers the token and server fields.
 *
 * The location flow is read only until the first fix, to name the oblast the
 * phone is in. The plugin depends only on `:plugin-api` and Compose; `app` is
 * the composition root that hosts [DownloadsScreenContent].
 */
class DownloadsUiPlugin(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Plugin {
    private var scope: CoroutineScope? = null

    override suspend fun activate(context: PluginContext): Outcome<Unit> {
        val pluginScope = CoroutineScope(SupervisorJob() + dispatcher)
        scope?.cancel()
        scope = pluginScope

        val session =
            DownloadsUiSession(
                access = context.services.regionDownloads,
                fixes = context.services.location.fixes,
            )
        session.follow(pluginScope)
        session.load()

        context.addCommand(DownloadsRefreshCommand(session))
        context.addCommand(DownloadRegionCommand(session))
        context.addCommand(CancelRegionDownloadCommand(session))
        context.addCommand(RemoveRegionCommand(session))
        context.addCommand(SelectRegionCommand(session))
        context.addCommand(SetMapServerCommand(session))
        context.addCommand(SetMobileDataPolicyCommand(session))
        context.addCommand(UseMobileDataNowCommand(session))
        context.addStream(
            StreamDescriptor(
                id = DownloadsUiContract.stateStream,
                serializer = DownloadsState.serializer(),
                flow = session.state,
            ),
        )
        return Outcome.Success(Unit)
    }

    override fun deactivate() {
        scope?.cancel()
        scope = null
    }
}
