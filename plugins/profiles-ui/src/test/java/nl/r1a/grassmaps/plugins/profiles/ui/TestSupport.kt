package nl.r1a.grassmaps.plugins.profiles.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import nl.r1a.grassmaps.plugin.api.command.Command
import nl.r1a.grassmaps.plugin.api.contract.PluginContext
import nl.r1a.grassmaps.plugin.api.contract.PluginId
import nl.r1a.grassmaps.plugin.api.contract.StreamId
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.host.ElevationAccess
import nl.r1a.grassmaps.plugin.api.host.PluginServices
import nl.r1a.grassmaps.plugin.api.host.ProfileSettingsAccess
import nl.r1a.grassmaps.plugin.api.host.StreamAccess
import nl.r1a.grassmaps.plugin.api.host.StreamDescriptor
import nl.r1a.grassmaps.plugin.api.model.Camera
import nl.r1a.grassmaps.plugin.api.model.LocationFix
import nl.r1a.grassmaps.plugin.api.model.ProfilesState
import nl.r1a.grassmaps.plugin.api.provider.Provider
import nl.r1a.grassmaps.plugin.api.service.Clock
import nl.r1a.grassmaps.plugin.api.service.LocationService
import nl.r1a.grassmaps.plugin.api.widget.WidgetDescriptor

internal class RecordingContext(
    override val services: PluginServices,
) : PluginContext {
    override val pluginId: PluginId = PluginId(ProfilesUiContract.PLUGIN_ID)

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

internal class FakeServices(
    override val profileSettings: ProfileSettingsAccess,
) : PluginServices {
    override val streams: StreamAccess =
        object : StreamAccess {
            override fun <T : Any> subscribe(streamId: StreamId): Flow<T> = emptyFlow()
        }

    override val location: LocationService =
        object : LocationService {
            override val fixes: Flow<LocationFix?> = emptyFlow()

            override fun hasPermission(): Boolean = false
        }

    override val elevation: ElevationAccess = ElevationAccess { Outcome.Success(null) }

    override val camera: Flow<Camera> = emptyFlow()

    override val clock: Clock = Clock { 0L }
}

/** Records the calls and answers with a fixed state or a fixed failure. */
internal class FakeProfileAccess(
    var answer: Outcome<ProfilesState> = Outcome.Success(ProfilesState()),
) : ProfileSettingsAccess {
    val calls = mutableListOf<String>()

    override fun state(): Outcome<ProfilesState> = record("state")

    override fun create(
        name: String,
        from: String,
    ): Outcome<ProfilesState> = record("create:$name:$from")

    override fun copySettings(
        from: String,
        to: String,
        groups: Set<String>,
    ): Outcome<ProfilesState> = record("copy:$from:$to:${groups.sorted()}")

    override fun reset(
        profile: String,
        groups: Set<String>,
    ): Outcome<ProfilesState> = record("reset:$profile:${groups.sorted()}")

    override fun undo(): Outcome<ProfilesState> = record("undo")

    private fun record(call: String): Outcome<ProfilesState> {
        calls += call
        return answer
    }

    companion object {
        val NOT_FOUND: Outcome<ProfilesState> = Outcome.Failure(AppError.NotFound)
    }
}
