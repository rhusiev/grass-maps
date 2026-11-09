package nl.r1a.grassmap.plugins.share

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.ElevationAccess
import nl.r1a.grassmap.plugin.api.host.PlatformActions
import nl.r1a.grassmap.plugin.api.host.PluginServices
import nl.r1a.grassmap.plugin.api.host.StreamAccess
import nl.r1a.grassmap.plugin.api.host.StreamDescriptor
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.provider.Provider
import nl.r1a.grassmap.plugin.api.service.Clock
import nl.r1a.grassmap.plugin.api.service.LocationService
import nl.r1a.grassmap.plugin.api.widget.WidgetDescriptor

/**
 * A typed fake of the platform seam. It records the exact value of every call
 * and returns whatever the test set, so the commands can be checked without an
 * Android clipboard or an intent.
 */
internal class RecordingActions : PlatformActions {
    val calls = mutableListOf<String>()
    var result: Outcome<Unit> = Outcome.Success(Unit)

    override fun copyToClipboard(text: String): Outcome<Unit> {
        calls += "copy:$text"
        return result
    }

    override fun shareText(
        text: String,
        subject: String?,
    ): Outcome<Unit> {
        calls += "share:$subject:$text"
        return result
    }

    override fun openUri(uri: String): Outcome<Unit> {
        calls += "open:$uri"
        return result
    }
}

/** The plugin services a test needs, with the location and camera flows it sets. */
internal class FakeServices(
    private val fixesFlow: Flow<LocationFix?> = emptyFlow(),
    private val cameraFlow: Flow<Camera> = emptyFlow(),
    override val platformActions: PlatformActions = RecordingActions(),
) : PluginServices {
    override val streams: StreamAccess =
        object : StreamAccess {
            override fun <T : Any> subscribe(streamId: StreamId): Flow<T> = emptyFlow()
        }

    override val location: LocationService =
        object : LocationService {
            override val fixes: Flow<LocationFix?> = this@FakeServices.fixesFlow

            override fun hasPermission(): Boolean = true
        }

    override val elevation: ElevationAccess = ElevationAccess { Outcome.Success(null) }

    override val camera: Flow<Camera> = this@FakeServices.cameraFlow

    override val clock: Clock = Clock { 0L }
}

/** Records the contributions [SharePlugin.activate] adds. */
internal class RecordingContext(
    override val services: PluginServices,
) : PluginContext {
    override val pluginId: PluginId = PluginId(ShareContract.PLUGIN_ID)

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
