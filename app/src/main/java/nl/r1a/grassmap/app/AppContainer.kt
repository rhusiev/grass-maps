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
import nl.r1a.grassmap.core.services.DeviceInfo
import nl.r1a.grassmap.core.services.Diagnostics
import nl.r1a.grassmap.core.services.InMemoryLocationPermissionStore
import nl.r1a.grassmap.core.services.LocationPermissionStore
import nl.r1a.grassmap.core.services.LocationSource
import nl.r1a.grassmap.core.services.NoLocationSource
import nl.r1a.grassmap.core.services.PrivacyNetworkGate
import nl.r1a.grassmap.core.services.PrivacySwitchSettings
import nl.r1a.grassmap.core.services.diagnosticsCommands
import nl.r1a.grassmap.places.PlacesAccess
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.host.StreamAccess
import nl.r1a.grassmap.plugin.api.manifest.GeneratedCommand
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine
import nl.r1a.grassmap.plugin.api.service.NetworkGate
import nl.r1a.grassmap.plugin.api.service.PrivacySwitches
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugins.dev.tools.DevToolsContract
import nl.r1a.grassmap.regions.GateNetworkAccess
import nl.r1a.grassmap.regions.HttpDownloadTransport
import nl.r1a.grassmap.regions.NetworkAccess
import nl.r1a.grassmap.regions.RegionDownloader
import nl.r1a.grassmap.regions.RegionPaths
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
    private val locationPermissionStore: LocationPermissionStore = InMemoryLocationPermissionStore(),
    private val routingFilesProvider: () -> File? = { null },
    private val searchFilesProvider: () -> File? = { null },
    private val assetReader: (String) -> ByteArray? = { null },
    private val regionsRootProvider: () -> File? = { null },
    private val diagnosticsRootProvider: () -> File? = { null },
    private val deviceInfoProvider: () -> DeviceInfo = { DeviceInfo.UNKNOWN },
    private val debuggable: () -> Boolean = { false },
    private val extraCommands: (MapSessionHolder) -> List<Command<*, *>> = { emptyList() },
    val devTools: DevToolsRuntime = NoDevToolsRuntime,
    private val aiControlEnabled: () -> Boolean = { false },
) {
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The process-owned map binding that host commands write through. */
    val mapSession: MapSessionHolder = MapSessionHolder()

    /** The process-owned location collector; it survives Activity recreation. */
    val locationController: LocationController by lazy {
        LocationController(locationSource, mapSession, processScope).also { it.start() }
    }

    /** The permission decision for the position control, bound by the activity. */
    val locationPermission: LocationPermissionCoordinator =
        LocationPermissionCoordinator(locationSource, locationPermissionStore)

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

    /** The offline search indexes over the app's external files directory. */
    val places: PlacesAccess by lazy {
        PlacesAccess(searchFilesProvider(), log = { message -> Log.i(SEARCH_TAG, message) })
    }

    /** The track import path over the generated `file.gpx` provider. */
    val tracks: TrackAccess by lazy {
        TrackAccess(providerRegistry, GeneratedPlugins.providers)
    }

    /**
     * The v1 privacy switches. Region downloads name
     * [nl.r1a.grassmap.core.services.NetworkSwitches.regionUpdates]; the real
     * gate refuses the request while the switch is off.
     */
    private val privacySwitches: PrivacySwitches = PrivacySwitchSettings()
    private val networkGate: NetworkGate = PrivacyNetworkGate(privacySwitches)

    /** The production network path for region downloads. */
    val regionNetwork: NetworkAccess = GateNetworkAccess(networkGate)

    /** The region store root, or null when the app has no files directory. */
    val regionsRoot: File? by lazy { regionsRootProvider() }

    /** The region downloader over the app-private region store, or null without a root. */
    val regionDownloader: RegionDownloader? by lazy {
        regionsRoot?.let { root ->
            RegionDownloader(
                transport = HttpDownloadTransport(),
                paths = RegionPaths(root),
                log = { message -> Log.i(REGIONS_TAG, message) },
            )
        }
    }

    /**
     * The diagnostic ring, settings and bundle writer over the app-private
     * `diagnostics/` directory. Constructing it reads the settings file, so the
     * lazy is resolved by an IO-dispatched command or the crash handler, never
     * by the Main thread.
     */
    val diagnostics: Diagnostics? by lazy {
        diagnosticsRootProvider()?.let { root ->
            Diagnostics(
                directory = root,
                deviceInfo = deviceInfoProvider,
                defaultLoggingEnabled = debuggable(),
            )
        }
    }

    private val hostCommands: List<Command<*, *>> by lazy {
        mapCommands(mapSession, locationController, locationPermission) +
            routeCommands(mapSession, { routingEngine }, { dataSources }) +
            navCommands(mapSession, { routingEngine }) +
            searchCommands { places } +
            trackCommands({ tracks }, assetReader) +
            regionCommands({ regionDownloader }, regionNetwork, debuggable) +
            extraCommands(mapSession) +
            diagnosticsCommands { diagnostics }
    }

    /** The catalog entries for the host commands; the debug channel lists these. */
    val controlCommands: List<GeneratedCommand> by lazy { generatedMapCommands(hostCommands) }

    /**
     * Whether the development-tools panel and its commands are available. The
     * architecture puts them behind the AI-control switch; until T-0610 wires
     * that switch, this reads the predicate the build supplies.
     */
    val devToolsVisible: Boolean = aiControlEnabled()

    val pluginHost: PluginHost by lazy {
        PluginHost(
            plugins = GeneratedPlugins.plugins,
            streams = GeneratedStreams.streams,
            commands = GeneratedPlugins.commands,
            hostCommands = hostCommands,
            dispatcher = dispatchers.default,
            devToolsAccess = devTools,
        )
    }

    val commandBus: CommandBus by lazy {
        CommandBus(
            host = pluginHost,
            commands = GeneratedPlugins.commands + generatedMapCommands(hostCommands),
            dispatchers = dispatchers,
            switchEnabled = { switch ->
                if (switch == DevToolsContract.aiControlSwitch) {
                    aiControlEnabled()
                } else {
                    switchEnabled(switch)
                }
            },
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
        const val SEARCH_TAG = "GrassMapsSearch"
        const val REGIONS_TAG = "GrassMapsRegions"
        const val ROUTING_PACK_DIRECTORY = "valhalla"
    }
}
