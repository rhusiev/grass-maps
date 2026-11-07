package nl.r1a.grassmap.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.r1a.grassmap.app.generated.GeneratedPlugins
import nl.r1a.grassmap.app.generated.GeneratedStreams
import nl.r1a.grassmap.core.CommandBus
import nl.r1a.grassmap.core.CommandDispatchers
import nl.r1a.grassmap.core.PluginHost
import nl.r1a.grassmap.core.ProviderRegistry
import nl.r1a.grassmap.core.StreamRegistry
import nl.r1a.grassmap.core.services.LocationSource
import nl.r1a.grassmap.core.services.NoLocationSource
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.host.StreamAccess
import nl.r1a.grassmap.plugin.api.service.Strings

/**
 * The one composition root. It wires the generated registries to the core host;
 * modules receive plugin-api interfaces from here.
 */
class AppContainer(
    val strings: Strings,
    private val dispatchers: CommandDispatchers = CommandDispatchers(),
    private val switchEnabled: (SwitchId) -> Boolean = { false },
    private val locationSource: LocationSource = NoLocationSource,
) {
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The process-owned map binding that host commands write through. */
    val mapSession: MapSessionHolder = MapSessionHolder()

    /** The process-owned location collector; it survives Activity recreation. */
    val locationController: LocationController by lazy {
        LocationController(locationSource, mapSession, processScope).also { it.start() }
    }

    private val hostCommands: List<Command<*, *>> by lazy { mapCommands(mapSession, locationController) }

    val pluginHost: PluginHost by lazy {
        PluginHost(
            plugins = GeneratedPlugins.plugins,
            streams = GeneratedStreams.streams,
            commands = GeneratedPlugins.commands,
            hostCommands = hostCommands,
            dispatcher = dispatchers.default,
        )
    }

    val commandBus: CommandBus by lazy {
        CommandBus(
            host = pluginHost,
            commands = GeneratedPlugins.commands + generatedMapCommands(hostCommands),
            dispatchers = dispatchers,
            switchEnabled = switchEnabled,
        )
    }

    val streamAccess: StreamAccess by lazy {
        StreamRegistry(pluginHost)
    }

    val providerRegistry: ProviderRegistry by lazy {
        ProviderRegistry(pluginHost)
    }
}
