package nl.r1a.grassmap.app

import android.util.Log
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
import nl.r1a.grassmap.plugin.api.manifest.GeneratedCommand
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.routing.ValhallaRoutingEngine
import java.io.File

/**
 * The one composition root. It wires the generated registries to the core host;
 * modules receive plugin-api interfaces from here.
 */
class AppContainer(
    val strings: Strings,
    private val dispatchers: CommandDispatchers = CommandDispatchers(),
    private val switchEnabled: (SwitchId) -> Boolean = { false },
    private val locationSource: LocationSource = NoLocationSource,
    private val routingFilesProvider: () -> File? = { null },
) {
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The process-owned map binding that host commands write through. */
    val mapSession: MapSessionHolder = MapSessionHolder()

    /** The process-owned location collector; it survives Activity recreation. */
    val locationController: LocationController by lazy {
        LocationController(locationSource, mapSession, processScope).also { it.start() }
    }

    /**
     * The Valhalla engine over the pack copied to the device. The file lookup
     * happens here, on the first route, never during app startup: resolving the
     * external files directory reads the disk and StrictMode forbids that on
     * Main. A missing directory or pack becomes a typed failure on the route.
     */
    val routingEngine: RoutingEngine? by lazy {
        routingFilesProvider()?.let { root ->
            ValhallaRoutingEngine(
                packDirectory = File(root, ROUTING_PACK_DIRECTORY),
                configDirectory = root,
                log = { message -> Log.i(ROUTING_TAG, message) },
            )
        }
    }

    private val hostCommands: List<Command<*, *>> by lazy {
        mapCommands(mapSession, locationController) +
            routeCommands(mapSession, { routingEngine }, { dataSources }) +
            navCommands(mapSession, { routingEngine })
    }

    /** The catalog entries for the host commands; the debug channel lists these. */
    val controlCommands: List<GeneratedCommand> by lazy { generatedMapCommands(hostCommands) }

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

    /** The synthetic DataSource; the route command reads its cost modifiers. */
    val dataSources: DataSourceAccess by lazy {
        DataSourceAccess(providerRegistry)
    }

    private companion object {
        const val ROUTING_TAG = "GrassMapsRouting"
        const val ROUTING_PACK_DIRECTORY = "valhalla"
    }
}
