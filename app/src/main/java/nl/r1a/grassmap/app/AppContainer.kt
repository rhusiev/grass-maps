package nl.r1a.grassmap.app

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import nl.r1a.grassmap.app.generated.GeneratedPlugins
import nl.r1a.grassmap.app.generated.GeneratedStreams
import nl.r1a.grassmap.core.CommandBus
import nl.r1a.grassmap.core.CommandDispatchers
import nl.r1a.grassmap.core.PluginHost
import nl.r1a.grassmap.core.ProviderRegistry
import nl.r1a.grassmap.core.StreamRegistry
import nl.r1a.grassmap.core.services.DEFAULT_PROFILE
import nl.r1a.grassmap.core.services.DeviceInfo
import nl.r1a.grassmap.core.services.Diagnostics
import nl.r1a.grassmap.core.services.FavouritesBackupRecorder
import nl.r1a.grassmap.core.services.InMemoryLocationPermissionStore
import nl.r1a.grassmap.core.services.LocationPermissionStore
import nl.r1a.grassmap.core.services.LocationSource
import nl.r1a.grassmap.core.services.NoHeadingSource
import nl.r1a.grassmap.core.services.NoLocationSource
import nl.r1a.grassmap.core.services.PrivacyNetworkGate
import nl.r1a.grassmap.core.services.PrivacySwitchSettings
import nl.r1a.grassmap.core.services.UnitSettings
import nl.r1a.grassmap.core.services.diagnosticsCommands
import nl.r1a.grassmap.map.DistanceByTapSetting
import nl.r1a.grassmap.places.PlacesAccess
import nl.r1a.grassmap.places.SearchScreenSession
import nl.r1a.grassmap.places.searchScreenCommands
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.NoPlatformActions
import nl.r1a.grassmap.plugin.api.host.PlatformActions
import nl.r1a.grassmap.plugin.api.host.StreamAccess
import nl.r1a.grassmap.plugin.api.manifest.GeneratedCommand
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine
import nl.r1a.grassmap.plugin.api.service.Clock
import nl.r1a.grassmap.plugin.api.service.HeadingSource
import nl.r1a.grassmap.plugin.api.service.NetworkGate
import nl.r1a.grassmap.plugin.api.service.PrivacySwitches
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.api.service.SystemClock
import nl.r1a.grassmap.plugins.coordinates.Coordinates
import nl.r1a.grassmap.plugins.dev.tools.DevToolsContract
import nl.r1a.grassmap.plugins.map.controls.CameraModeController
import nl.r1a.grassmap.plugins.map.controls.MapControls
import nl.r1a.grassmap.plugins.map.controls.PositionOffsetFraction
import nl.r1a.grassmap.plugins.map.controls.PositionOffsetProvider
import nl.r1a.grassmap.plugins.point.info.PointInfo
import nl.r1a.grassmap.plugins.point.info.SelectedPointStore
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
    val distanceByTap: DistanceByTapSetting = DistanceByTapSetting { false },
    private val headingSource: HeadingSource = NoHeadingSource,
    private val positionOffsetProvider: PositionOffsetProvider = PositionOffsetProvider.CENTERED,
    private val unitSettingsFileProvider: () -> File? = { null },
    private val favouritesRootProvider: () -> File? = { null },
    private val favouritesBackupClock: Clock = SystemClock,
    private val favouritesBackupScope: CoroutineScope? = null,
    private val favouritesBackupDebounceMillis: Long = FavouritesBackupRecorder.DEFAULT_DEBOUNCE_MILLIS,
    /** Clipboard, share-sheet and `ACTION_VIEW` effects (T-0210). */
    val platformActions: PlatformActions = NoPlatformActions,
) {
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val backupScope: CoroutineScope = favouritesBackupScope ?: processScope

    /** The process-owned map binding that host commands write through. */
    val mapSession: MapSessionHolder = MapSessionHolder()

    /**
     * The point the map long press selected (T-0112). The gesture writes it
     * and `plugins/point-info` reads it; both share the process-wide store, so
     * a recomposition or a rebuilt surface keeps the selection.
     */
    val selectedPoint: SelectedPointStore = PointInfo.selection

    /** Owns the free, follow and heading cycle across Activity recreation. */
    val cameraModes: CameraModeController by lazy {
        CameraModeController(
            host = mapSession::hostOrNull,
            heading = headingSource.headingDegrees,
            store = MapControls.modes,
            log = { message -> Log.w(MAP_CONTROLS_TAG, message) },
        )
    }

    /**
     * The link-in router (T-0211). The Activity submits the intent URI before
     * a map surface binds, so the move waits here until the screen drains it.
     */
    val links: MapLinks by lazy {
        MapLinks(mapSession, cameraModes) { message -> Log.i(LINKS_TAG, message) }
    }

    /** The marker offset for the active profile; T-0601 reads it per profile. */
    val positionOffset: PositionOffsetFraction
        get() = positionOffsetProvider.offsetForProfile(DEFAULT_PROFILE.value)

    /**
     * The global length unit setting. Resolving it reads the settings file, so
     * the first access is always from an IO-dispatched command or the map's
     * IO-loaded state, never Main.
     */
    val unitSettings: UnitSettings by lazy { UnitSettings(unitSettingsFileProvider()) }

    /** The compass; the map subscribes while it is visible. */
    val heading: StateFlow<Double?>
        get() = headingSource.headingDegrees

    /** The process-owned location collector; it survives Activity recreation. */
    val locationController: LocationController by lazy {
        LocationController(locationSource, processScope, cameraModes).also { it.start() }
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

    /**
     * The offline search indexes over the app's external files directory, with
     * the coordinate parser in front of them (T-0206): a typed coordinate is a
     * result row even when no index is installed.
     */
    val places: PlacesAccess by lazy {
        PlacesAccess(
            externalFilesRoot = searchFilesProvider(),
            coordinateParser = Coordinates.parser,
            log = { message -> Log.i(SEARCH_TAG, message) },
        )
    }

    /**
     * The search screen's state (T-0203). The shell sends each keystroke through
     * the command bus; this session holds the one query path and publishes the
     * result set both the list and the map markers draw.
     */
    val searchScreen: SearchScreenSession by lazy {
        val indexes = places
        SearchScreenSession { request ->
            indexes.search(
                indexName = request.index,
                query = request.query,
                context = request.context,
                limit = request.limit,
                cancellation = request.cancellation,
            )
        }
    }

    /** The track import path over the generated `file.gpx` provider. */
    val tracks: TrackAccess by lazy {
        TrackAccess(providerRegistry, GeneratedPlugins.providers)
    }

    /**
     * The favourites library under the app's private files root (T-0201).
     *
     * The access caches the parsed library, so [forgetFavourites] drops it after
     * a backup restore replaces the file on disk.
     */
    val favourites: FavouritesAccess
        get() =
            cachedFavourites ?: synchronized(favouritesLock) {
                cachedFavourites ?: FavouritesAccess(favouritesRootProvider()).also { access -> cachedFavourites = access }
            }

    /**
     * The automatic local backup of the favourites library (T-0216), or null
     * when the build has no files root.
     */
    val favouritesBackup: LocalFavouritesBackup? by lazy {
        favouritesRootProvider()?.let { root ->
            LocalFavouritesBackup(
                root = root,
                favourites = { favourites },
                scope = backupScope,
                clock = favouritesBackupClock,
                debounceMillis = favouritesBackupDebounceMillis,
                onRestored = ::forgetFavourites,
                onResult = { result ->
                    if (result is Outcome.Failure) {
                        Log.w(FAVOURITES_TAG, "favourites backup failed: ${result.error}")
                    }
                },
            )
        }
    }

    private val favouritesLock = Any()

    /**
     * The lists screen access over the favourites library (T-0213). It is the
     * only writer the `favourites.ui` plugin gets, and it writes through the
     * same store, so the GPX file and its group definitions always match the
     * screen state.
     */
    val favouritesLists: StoreFavouritesLists by lazy { StoreFavouritesLists { favourites } }

    @Volatile
    private var cachedFavourites: FavouritesAccess? = null

    /** Drops the parsed library after a restore replaced its file. */
    internal fun forgetFavourites() {
        synchronized(favouritesLock) { cachedFavourites = null }
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
        mapCommands(mapSession, locationController, locationPermission, cameraModes) +
            routeCommands(mapSession, { routingEngine }, { dataSources }) +
            navCommands(mapSession, { routingEngine }) +
            searchCommands { places } +
            searchScreenCommands(searchScreen) { message -> Log.i(SEARCH_TAG, message) } +
            trackCommands({ tracks }, assetReader) +
            favouriteCommands({ favourites }, assetReader).map { command ->
                observedFavouriteCommand(command) { favouritesBackup?.onMutation() }
            } +
            regionCommands({ regionDownloader }, regionNetwork, debuggable) +
            UnitsCommand({ unitSettings }) +
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
            locationService = locationSource,
            cameraFlow = mapSession.camera,
            devToolsAccess = devTools,
            favouritesListsAccess = favouritesLists,
            platformActions = platformActions,
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
        const val MAP_CONTROLS_TAG = "GrassMapsMapControls"
        const val LINKS_TAG = "GrassMapsLinks"
        const val FAVOURITES_TAG = "GrassMapsFavourites"
        const val ROUTING_PACK_DIRECTORY = "valhalla"
    }
}
