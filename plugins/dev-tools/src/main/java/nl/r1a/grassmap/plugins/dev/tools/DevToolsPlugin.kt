package nl.r1a.grassmap.plugins.dev.tools

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.DevToolsAccess
import nl.r1a.grassmap.plugin.api.host.StreamDescriptor

/**
 * The user-facing development-tools plugin (T-0114).
 *
 * It owns no location code and no measurement code: the host passes the GPX
 * replay seam, the frame rate and the memory figure through
 * [nl.r1a.grassmap.plugin.api.host.PluginServices.devTools]. Activation
 * registers one start command, one stop command and one state stream that the
 * panel and the control channel both bind by id.
 */
class DevToolsPlugin(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
) : Plugin {
    private var scope: CoroutineScope? = null

    override suspend fun activate(context: PluginContext): Outcome<Unit> {
        val devTools = context.services.devTools
        val state = MutableStateFlow(devTools.panelState())
        val pluginScope = CoroutineScope(SupervisorJob() + dispatcher)
        scope?.cancel()
        scope = pluginScope

        pluginScope.launch {
            while (isActive) {
                delay(pollIntervalMillis)
                state.value = devTools.panelState()
            }
        }

        context.addCommand(StartReplayCommand(devTools, state))
        context.addCommand(StopReplayCommand(devTools, state))
        context.addStream(
            StreamDescriptor(
                id = DevToolsContract.stateStream,
                serializer = DevToolsPanelState.serializer(),
                flow = state,
            ),
        )
        return Outcome.Success(Unit)
    }

    override fun deactivate() {
        scope?.cancel()
        scope = null
    }

    private companion object {
        const val DEFAULT_POLL_INTERVAL_MILLIS = 1_000L
    }
}

/** Reads the current host sample into the panel model. */
internal fun DevToolsAccess.panelState(): DevToolsPanelState {
    val metrics = metrics()
    return DevToolsPanelState(
        replayActive = isReplayActive(),
        framesPerSecond = metrics.framesPerSecond,
        usedMemoryBytes = metrics.usedMemoryBytes,
    )
}
