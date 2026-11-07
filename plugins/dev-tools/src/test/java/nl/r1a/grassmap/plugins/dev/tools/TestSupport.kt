package nl.r1a.grassmap.plugins.dev.tools

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.DevToolsAccess
import nl.r1a.grassmap.plugin.api.host.DevToolsMetrics
import nl.r1a.grassmap.plugin.api.host.ElevationAccess
import nl.r1a.grassmap.plugin.api.host.PluginServices
import nl.r1a.grassmap.plugin.api.host.StreamAccess
import nl.r1a.grassmap.plugin.api.host.StreamDescriptor
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.provider.Provider
import nl.r1a.grassmap.plugin.api.service.Clock
import nl.r1a.grassmap.plugin.api.service.LocationService
import nl.r1a.grassmap.plugin.api.widget.WidgetDescriptor

internal class FakeDevToolsAccess(
    var framesPerSecond: Double = 60.0,
    var usedMemoryBytes: Long = 128L * 1024 * 1024,
) : DevToolsAccess {
    var replayActive: Boolean = false
        private set

    override fun isReplayActive(): Boolean = replayActive

    override fun metrics(): DevToolsMetrics = DevToolsMetrics(framesPerSecond, usedMemoryBytes)

    override suspend fun startReplay(): Outcome<Unit> {
        replayActive = true
        return Outcome.Success(Unit)
    }

    override fun stopReplay() {
        replayActive = false
    }
}

internal class RecordingContext(
    override val services: PluginServices,
) : PluginContext {
    override val pluginId: PluginId = PluginId(DevToolsContract.PLUGIN_ID)

    val commands = mutableListOf<Command<*, *>>()
    val streams = mutableListOf<StreamDescriptor<*>>()
    val providers = mutableListOf<Provider>()
    val widgets = mutableListOf<WidgetDescriptor>()

    override fun addCommand(command: Command<*, *>) {
        commands += command
    }

    override fun addStream(stream: StreamDescriptor<*>) {
        streams += stream
    }

    override fun addProvider(provider: Provider) {
        providers += provider
    }

    override fun addWidget(widget: WidgetDescriptor) {
        widgets += widget
    }
}

@Suppress("UNCHECKED_CAST")
internal fun RecordingContext.stateFlow(): Flow<DevToolsPanelState> =
    streams.single { it.id == DevToolsContract.stateStream }.flow as Flow<DevToolsPanelState>

internal fun context(devTools: DevToolsAccess): RecordingContext = RecordingContext(FakeServices(devTools))

private class FakeServices(
    override val devTools: DevToolsAccess,
) : PluginServices {
    override val streams: StreamAccess =
        object : StreamAccess {
            override fun <T : Any> subscribe(streamId: StreamId): Flow<T> = emptyFlow()
        }

    override val location: LocationService =
        object : LocationService {
            override val fixes: Flow<LocationFix?> = emptyFlow()

            override fun hasPermission(): Boolean = false

            override fun requiresPermission(): Boolean = false
        }

    override val elevation: ElevationAccess = ElevationAccess { Outcome.Success(null) }

    override val camera: Flow<Camera> = emptyFlow()

    override val clock: Clock = Clock { 0L }
}
