package nl.r1a.grassmap.app

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.alerts.AlertEffectSink
import nl.r1a.grassmap.alerts.AlertHost
import nl.r1a.grassmap.alerts.AlertKind
import nl.r1a.grassmap.alerts.AlertSettingsStore
import nl.r1a.grassmap.alerts.BeepHost
import nl.r1a.grassmap.alerts.BeepSink
import nl.r1a.grassmap.alerts.NoopAlertEffectSink
import nl.r1a.grassmap.alerts.NoopBeepSink
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
import nl.r1a.grassmap.core.services.OnlineSearchSettings
import nl.r1a.grassmap.core.services.PrivacyNetworkGate
import nl.r1a.grassmap.core.services.PrivacySwitchSettings
import nl.r1a.grassmap.core.services.RoutingOptionsSettings
import nl.r1a.grassmap.core.services.SearchHistorySettings
import nl.r1a.grassmap.core.services.SharedLocationSource
import nl.r1a.grassmap.core.services.UnitSettings
import nl.r1a.grassmap.core.services.diagnosticsCommands
import nl.r1a.grassmap.map.DistanceByTapSetting
import nl.r1a.grassmap.navigation.NavigationCameraController
import nl.r1a.grassmap.places.MergedPlaceSearch
import nl.r1a.grassmap.places.PlacesAccess
import nl.r1a.grassmap.places.SearchHistorySetting
import nl.r1a.grassmap.places.SearchHistoryStore
import nl.r1a.grassmap.places.SearchScreenSession
import nl.r1a.grassmap.places.searchHistoryCommands
import nl.r1a.grassmap.places.searchScreenCommands
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.ElevationAccess
import nl.r1a.grassmap.plugin.api.host.NoElevationAccess
import nl.r1a.grassmap.plugin.api.host.NoPlatformActions
import nl.r1a.grassmap.plugin.api.host.NoRegionDownloadsAccess
import nl.r1a.grassmap.plugin.api.host.PlatformActions
import nl.r1a.grassmap.plugin.api.host.RegionAccess
import nl.r1a.grassmap.plugin.api.host.RoutingEngineAccess
import nl.r1a.grassmap.plugin.api.host.RoutingOptionsAccess
import nl.r1a.grassmap.plugin.api.host.StreamAccess
import nl.r1a.grassmap.plugin.api.manifest.GeneratedCommand
import nl.r1a.grassmap.plugin.api.model.InstalledPack
import nl.r1a.grassmap.plugin.api.model.MobileDataPolicy
import nl.r1a.grassmap.plugin.api.provider.CoordinateParser
import nl.r1a.grassmap.plugin.api.provider.PlaceProvider
import nl.r1a.grassmap.plugin.api.provider.PlaceProviderContract
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine
import nl.r1a.grassmap.plugin.api.provider.RoutingEngineContract
import nl.r1a.grassmap.plugin.api.service.Clock
import nl.r1a.grassmap.plugin.api.service.HeadingSource
import nl.r1a.grassmap.plugin.api.service.LogLevel
import nl.r1a.grassmap.plugin.api.service.Logger
import nl.r1a.grassmap.plugin.api.service.NetworkGate
import nl.r1a.grassmap.plugin.api.service.NetworkSwitches
import nl.r1a.grassmap.plugin.api.service.PrivacySwitches
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.api.service.SwitchState
import nl.r1a.grassmap.plugin.api.service.SystemClock
import nl.r1a.grassmap.plugins.coordinates.Coordinates
import nl.r1a.grassmap.plugins.map.controls.CameraModeController
import nl.r1a.grassmap.plugins.map.controls.MapControls
import nl.r1a.grassmap.plugins.map.controls.PositionOffsetFraction
import nl.r1a.grassmap.plugins.map.controls.PositionOffsetProvider
import nl.r1a.grassmap.plugins.point.info.PointInfo
import nl.r1a.grassmap.plugins.point.info.SelectedPointStore
import nl.r1a.grassmap.plugins.proximity.alerts.ProximityAlertHost
import nl.r1a.grassmap.plugins.proximity.alerts.ProximityAlertSink
import nl.r1a.grassmap.plugins.raster.RasterPlacement
import nl.r1a.grassmap.plugins.raster.RasterSource
import nl.r1a.grassmap.plugins.route.layer.RouteLayer
import nl.r1a.grassmap.plugins.route.warnings.RouteWarningScanner
import nl.r1a.grassmap.plugins.track.layer.TrackLayer
import nl.r1a.grassmap.plugins.track.library.TrackFolderStore
import nl.r1a.grassmap.plugins.way.alerts.WayAlertHost
import nl.r1a.grassmap.plugins.way.alerts.WayAlertSink
import nl.r1a.grassmap.regions.AutoUpdateNotifier
import nl.r1a.grassmap.regions.AutoUpdateRun
import nl.r1a.grassmap.regions.AutoUpdater
import nl.r1a.grassmap.regions.DownloadPolicy
import nl.r1a.grassmap.regions.FileAutoUpdateLedger
import nl.r1a.grassmap.regions.GateNetworkAccess
import nl.r1a.grassmap.regions.HttpDownloadTransport
import nl.r1a.grassmap.regions.InstalledPacks
import nl.r1a.grassmap.regions.MeteredNetworkMonitor
import nl.r1a.grassmap.regions.MountedRegion
import nl.r1a.grassmap.regions.MountedRoutingPacks
import nl.r1a.grassmap.regions.NetworkAccess
import nl.r1a.grassmap.regions.NeverMetered
import nl.r1a.grassmap.regions.RegionDownloader
import nl.r1a.grassmap.regions.RegionElevation
import nl.r1a.grassmap.regions.RegionMountResolver
import nl.r1a.grassmap.regions.RegionPaths
import nl.r1a.grassmap.regions.RegionReaders
import nl.r1a.grassmap.regions.RegionStore
import nl.r1a.grassmap.regions.SupersededVersions
import nl.r1a.grassmap.routing.RouteHistorySetting
import nl.r1a.grassmap.routing.RouteHistoryStore
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
    private val tracksRootProvider: () -> File? = { null },
    private val diagnosticsRootProvider: () -> File? = { null },
    private val deviceInfoProvider: () -> DeviceInfo = { DeviceInfo.UNKNOWN },
    private val debuggable: () -> Boolean = { false },
    private val extraCommands: (MapSessionHolder) -> List<Command<*, *>> = { emptyList() },
    val devTools: DevToolsRuntime = NoDevToolsRuntime,
    /** The development-tools overlay; only a debug build supplies one. */
    val devToolsPanel: (@Composable (Modifier) -> Unit)? = null,
    /** The synthetic DataSource plugin; null when this build does not package it. */
    private val dataSourcePluginId: PluginId? = null,
    private val aiControlEnabled: () -> Boolean = { false },
    val distanceByTap: DistanceByTapSetting = DistanceByTapSetting { false },
    private val headingSource: HeadingSource = NoHeadingSource,
    private val positionOffsetProvider: PositionOffsetProvider = PositionOffsetProvider.CENTERED,
    private val unitSettingsFileProvider: () -> File? = { null },
    private val searchHistoryFileProvider: () -> File? = { null },
    private val searchHistorySettingsFileProvider: () -> File? = { null },
    private val onlineSearchSettingsFileProvider: () -> File? = { null },
    private val contoursSettingsFileProvider: () -> File? = { null },
    private val rasterSettingsFileProvider: () -> File? = { null },
    private val mapStyleSettingsFileProvider: () -> File? = { null },
    private val routeHistoryFileProvider: () -> File? = { null },
    private val routingOptionsFileProvider: () -> File? = { null },
    private val alertSettingsFileProvider: () -> File? = { null },
    private val favouritesRootProvider: () -> File? = { null },
    private val favouritesBackupClock: Clock = SystemClock,
    private val favouritesBackupScope: CoroutineScope? = null,
    private val favouritesBackupDebounceMillis: Long = FavouritesBackupRecorder.DEFAULT_DEBOUNCE_MILLIS,
    /** Clipboard, share-sheet and `ACTION_VIEW` effects (T-0210). */
    val platformActions: PlatformActions = NoPlatformActions,
    /** The system document picker behind [PlatformActions.saveFile] (T-0410). */
    val documentSaver: DocumentSaveCoordinator = DocumentSaveCoordinator(),
    /** The navigation foreground service (T-0314); absent in tests. */
    private val navigationService: NavigationServiceHandle = NoNavigationServiceHandle,
    /** The download foreground service and its inputs (T-0505); absent in tests. */
    private val downloadService: DownloadServiceHandle = NoDownloadServiceHandle,
    private val downloadsSettingsFileProvider: () -> File? = { null },
    private val meteredNetwork: MeteredNetworkMonitor = NeverMetered,
    /** The monthly map update (T-0508): its ledger file, wake-up and notice. Absent in tests. */
    private val autoUpdateLedgerFileProvider: () -> File? = { null },
    private val autoUpdateScheduler: AutoUpdateScheduler = NoAutoUpdateScheduler,
    private val autoUpdateNotifier: AutoUpdateNotifier = AutoUpdateNotifier {},
    private val autoUpdateClock: java.time.Clock = java.time.Clock.systemUTC(),
    /** The audio backend for turn beeps (T-0318); the app wires the Android one. */
    private val beepSink: BeepSink = NoopBeepSink,
    /** The tone and vibration backend for alerts (T-0317); Noop by default. */
    private val alertEffectSink: AlertEffectSink = NoopAlertEffectSink,
) {
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val backupScope: CoroutineScope = favouritesBackupScope ?: processScope

    /** The process-owned map binding that host commands write through. */
    val mapSession: MapSessionHolder = MapSessionHolder()

    /**
     * The deterministic navigation screen fixture the debug command fills
     * (T-0320). It is null in a release build; the live navigation state is the
     * only screen source unless a debug command explicitly shows a fixture.
     */
    internal val navigationScreenFixture: NavigationScreenFixtureStore = NavigationScreenFixtureStore()

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

    /**
     * The search history switch (T-0207). Resolving it reads the settings file,
     * so the first access happens in an IO-dispatched command or in the search
     * command, never on Main.
     */
    val searchHistorySettings: SearchHistorySettings by lazy { SearchHistorySettings(searchHistorySettingsFileProvider()) }

    /**
     * The separate "send my position" allowance for online search (T-0212).
     * Construction reads the settings file, so the search path and the screen
     * resolve it off Main.
     */
    val onlineSearchSettings: OnlineSearchSettings by lazy { OnlineSearchSettings(onlineSearchSettingsFileProvider()) }

    /**
     * The golden harness's launch override, applied when the settings object is
     * first resolved. It is not persisted: a capture must not rewrite the
     * user's saved layer choice.
     */
    @Volatile
    var contoursOverride: Boolean? = null

    /**
     * The map style the layers panel switches (T-0109). Construction reads the
     * settings file, so the map bundle resolves it off Main.
     */
    val mapStyleSettings: MapStyleSettings by lazy {
        MapStyleSettings(mapStyleSettingsFileProvider())
    }

    /**
     * The contour layer switch (T-0417). Construction reads the settings file,
     * so the map bundle and the layers panel resolve it off Main.
     */
    val contoursSettings: ContoursSettings by lazy {
        ContoursSettings(contoursSettingsFileProvider(), override = { contoursOverride })
    }

    /**
     * The online raster layer (T-0512): its source, placement and transparency
     * (T-0514). Construction reads the settings file, so the map screen
     * resolves it off Main.
     */
    val rasterSettings: RasterLayerSettings by lazy {
        RasterLayerSettings(rasterSettingsFileProvider())
    }

    /**
     * The online tile overlay (T-0512): the plugin provider, the tile client
     * and the engine interceptor every tile request must pass.
     */
    val rasterTiles: RasterTilesAccess by lazy {
        RasterTilesAccess(providerRegistry, networkGate)
    }

    /**
     * The per-profile routing options (T-0308). Construction reads the settings
     * file, so the planner asks it through the host and never on Main.
     */
    val routingOptionsSettings: RoutingOptionsSettings by lazy {
        RoutingOptionsSettings(routingOptionsFileProvider())
    }

    /**
     * The last searches (T-0207). It asks [searchHistorySettings] before it
     * writes or reads, so the switch stops the store itself rather than a
     * screen. A build with no files root writes nothing and reads as empty.
     */
    val searchHistory: SearchHistoryStore by lazy {
        SearchHistoryStore(
            file = searchHistoryFileProvider(),
            setting = SearchHistorySetting { searchHistorySettings.isEnabled() },
        )
    }

    /**
     * The last routes the planner produced (T-0304). It asks
     * [searchHistorySettings] before it writes or reads, so one "keep local
     * history" decision covers both the searches and the routes; the two files
     * stay separate because a query and a waypoint chain are different data.
     * A build with no files root writes nothing and reads as empty.
     */
    val routeHistory: RouteHistoryStore by lazy {
        RouteHistoryStore(
            file = routeHistoryFileProvider(),
            setting = RouteHistorySetting { searchHistorySettings.isEnabled() },
        )
    }

    /**
     * The one coordinate parser search and the planner's entry field use
     * (T-0206, T-0304). Both ask the same instance, so the notations one
     * accepts are the notations the other accepts.
     */
    val coordinateParser: CoordinateParser = Coordinates.parser

    /** The compass; the map subscribes while it is visible. */
    val heading: StateFlow<Double?>
        get() = headingSource.headingDegrees

    /**
     * One provider collection shared by the map's collector and an active
     * navigation session (T-0314). It registers platform providers once and
     * releases them when the last subscriber leaves, which is the release rule
     * for location outside navigation.
     */
    private val sharedLocation: LocationSource by lazy { SharedLocationSource(locationSource, processScope) }

    /**
     * The activity-scoped location collector; the activity starts it at
     * `STARTED` and stops it at `STOPPED`. Keeping it stopped outside the
     * foreground is what releases location when guidance is not running.
     */
    val locationController: LocationController by lazy {
        LocationController(sharedLocation, processScope, cameraModes)
    }

    /** The permission decision for the position control, bound by the activity. */
    val locationPermission: LocationPermissionCoordinator =
        LocationPermissionCoordinator(sharedLocation, locationPermissionStore)

    /**
     * The one-time Android 13 notification prompt, shared by every recreation of
     * the activity in this process. The process outlives an activity because the
     * navigation service keeps it while guidance runs.
     */
    val notificationPermission: NotificationPermissionPrompt = NotificationPermissionPrompt()

    /**
     * The `routing.engine` provider behind a host seam (T-0302). The plugin is
     * activated here rather than at startup, and the pack directory reaches it
     * through the host's pack access, so no routing path is read on Main.
     */
    private val routingEngineAccess: RoutingEngineAccess =
        RoutingEngineAccess {
            when (
                val resolved =
                    providerRegistry.invoke(ROUTING_ENGINE_PLUGIN, RoutingEngineContract.REFERENCE) { provider ->
                        when (val engine = provider as? RoutingEngine) {
                            null -> Outcome.Failure(AppError.Unsupported)
                            else -> Outcome.Success(engine)
                        }
                    }
            ) {
                is Outcome.Success -> resolved.value
                is Outcome.Failure -> resolved
            }
        }

    /** The engine a host command resolves on the first route (T-0301). */
    val routingEngine: suspend () -> RoutingEngine? = {
        ((routingEngineAccess.engine()) as? Outcome.Success)?.value
    }

    /**
     * The live navigation session (T-0314). Commands and the future navigation
     * screen start it; the foreground service keeps it and location alive while
     * the activity is gone, and stops both when the session ends.
     */
    val navigation: NavigationController by lazy {
        NavigationController(
            scope = processScope,
            fixes = sharedLocation.fixes,
            hasLocationPermission = { sharedLocation.hasPermission() },
            requiresLocationPermission = { sharedLocation.requiresPermission() },
            routing = routingEngine,
            service = navigationService,
            onProfileStarted = { profile -> alertSettings.activate(profile) },
            log = { message -> Log.i(NAVIGATION_TAG, message) },
        )
    }

    /**
     * The guidance camera over the current map host. It belongs to the process,
     * not to a screen composition: a re-created Activity mid-trip keeps the
     * switches the user set, and a trip started without a UI still drives the
     * camera once a host binds. [GuidanceStarter] applies the profile defaults
     * on a fresh start only.
     */
    internal val navigationCamera: NavigationCameraController by lazy {
        NavigationCameraController(
            host = mapSession::hostOrNull,
            log = { message -> Log.i(NAVIGATION_TAG, message) },
        ).also { camera ->
            processScope.launch { navigation.state.collect { state -> camera.onState(state) } }
        }
    }

    /**
     * Turn beeps (T-0318). The host is off until the `core.nav.beeps` debug
     * command turns it on; attaching here means it sees the same published
     * state the notification and a screen read.
     */
    val beeps: BeepHost by lazy {
        BeepHost(sink = beepSink).also { host ->
            host.attach(processScope, navigation.state)
        }
    }

    /**
     * The per-profile alert switches, output channels and chosen place kinds
     * (T-0413). Construction is cheap; [AlertSettingsStore.load] reads the file
     * from the IO dispatcher when the alert host is first built.
     */
    val alertSettings: AlertSettingsStore by lazy {
        AlertSettingsStore(
            file = alertSettingsFileProvider(),
            writeScope = processScope,
            log = { message -> Log.w(ALERTS_TAG, message) },
        )
    }

    /**
     * The alert host (T-0317). Its switches and channels start from the active
     * profile's stored values, and every change is written back to that
     * profile. Way alerts (T-0319) and proximity alerts (T-0413) are the
     * producers; a trip activates its profile in [NavigationController.start].
     */
    val alerts: AlertHost by lazy {
        AlertHost(
            initialSettings = alertSettings.current().settings,
            initialChannels = alertSettings.current().channels,
            effectSink = alertEffectSink,
            onSettingsChanged = { value ->
                alertSettings.setActive(alertSettings.current().copy(settings = value))
            },
            onChannelsChanged = { value ->
                alertSettings.setActive(alertSettings.current().copy(channels = value))
            },
        ).also { host ->
            processScope.launch {
                withContext(Dispatchers.IO) { alertSettings.load() }
                alertSettings.active.collect { profile -> host.replaceSettings(profile.settings, profile.channels) }
            }
        }
    }

    /**
     * The round 5 banner rules over the alert host's stream (T-0320). It is
     * attached once for the process, so an alert emitted between two screen
     * frames is still on the screen when the navigation screen collects it.
     */
    internal val navigationAlertBanners: NavigationAlertBanners by lazy {
        NavigationAlertBanners().also { presenter ->
            processScope.launch {
                alerts.events.collect { event -> presenter.onEvent(event, navigation.state.value) }
            }
            processScope.launch {
                navigation.state.collect { state -> presenter.onState(state) }
            }
        }
    }

    /**
     * The mounted regions, read once. The way-alert and relation readers use
     * every mounted pack (T-0516), and the mount is resolved once, so an
     * invalid mount is logged once.
     */
    private val mountedRegions: List<MountedRegion> by lazy {
        val root = regionsRoot ?: return@lazy emptyList()
        val mounts = RegionMountResolver(root).resolveAll()
        mounts.broken.firstOrNull()?.let { broken ->
            Log.w(REGIONS_TAG, "region mount invalid: ${broken.region} ${broken.problem}")
        }
        mounts.installed.also { installed ->
            mountedBaseline = InstalledPacks.of(root, installed)
        }
    }

    /**
     * The packs [mountedRegions] opened, set once when the readers first resolve.
     * The region store takes it as what the running process serves (T-0507).
     */
    @Volatile
    private var mountedBaseline: List<InstalledPack>? = null

    /**
     * The installed packs' way-alert features and route relations (T-0319,
     * T-0418). Every mounted pack answers (T-0516), in region-id order, so the
     * first pack that holds a shared feature or relation serves it. The reader
     * loads the native library and opens each component on the first query, off
     * Main; an older package without a component contributes nothing, and no
     * mounted pack is the inert source.
     */
    private val regionAccess: RegionAccess by lazy {
        RegionReaders.open(mountedRegions)
    }

    /** The mounted region's components for feature wiring outside this container. */
    val regionData: RegionAccess
        get() = regionAccess

    /**
     * The warning list for the planned route (T-0409). The scanner reads the
     * mounted region's way-alert component for fords, barriers and railway
     * crossings and the route's own annotated runs for steps and private ways. An
     * unmounted region is the inert source, so a plan without a pack still
     * gets its route-attribute warnings.
     */
    val routeWarnings: RouteWarningScanner by lazy { RouteWarningScanner(regionAccess) }

    /**
     * The first mounted region's DEM reader (T-0308), or null when no region is
     * mounted. The track importer keeps the null, so its correction pass can
     * report that the track kept its own heights (T-0403). The region's 30 m
     * add-on, when installed, is read first (T-0509). The add-on is resolved
     * once with the mount, so one installed while the app runs takes effect at
     * the next start, as an update does.
     */
    private val mountedElevation: RegionElevation? by lazy {
        mountedRegions.firstOrNull()?.let { mounted ->
            RegionElevation(mounted.directory, regionsRoot?.let { RegionMountResolver(it).elevationAddon(mounted.region) })
        }
    }

    /**
     * The mounted region's DEM heights (T-0308). The grid is opened on the
     * first query; a package without the component or with the old stand-in
     * reports no height, and an unmounted region is the inert source.
     */
    val elevationAccess: ElevationAccess by lazy {
        mountedElevation ?: NoElevationAccess
    }

    /**
     * Way alerts over the live navigation state (T-0319). The schedule asks
     * the region for the features on each route revision and the sink bridges
     * a due cue into the alert host's typed banner and effect path. The first
     * access starts the collector; [guidance] sets the profile.
     */
    val wayAlerts: WayAlertHost by lazy {
        WayAlertHost(
            region = regionAccess,
            sink =
                WayAlertSink { cue ->
                    alerts.onWayAlert(
                        kind = AlertKind.forWayFeature(cue.feature.kind),
                        fixTimeMillis = cue.fixTimeMillis,
                        distanceToFeatureMeters = cue.distanceAheadMeters,
                        featureKind = cue.feature.kind,
                    )
                },
            log = { message -> Log.i(WAY_ALERTS_TAG, message) },
        ).also { host ->
            processScope.launch {
                navigation.state.collect { state -> host.onState(state.toWayAlertState()) }
            }
        }
    }

    /**
     * The place kinds the active profile picked for proximity alerts (T-0413).
     * The alerts page writes them through [alertSettings]; this is the read
     * side the proximity plugin and [proximityAlertsPossible] use. The list
     * holds exact index kinds ("amenity=cafe").
     */
    val proximityPoiKinds: Set<String>
        get() = alertSettings.current().poiKinds

    /**
     * Proximity alerts (T-0413): a favourite, a track waypoint or a chosen
     * place kind announced once per approach, through the alert host's own
     * off-by-default switches. The source reads the favourites library, the
     * stored tracks and the offline index; the collector starts on the first
     * access and follows the same published state the notification reads.
     *
     * [ProximityAlertHost.canAlert] is this wiring's answer to whether any
     * place alert can be raised: one of the three [AlertHost] switches is on,
     * and an on `poi` switch has at least one kind to look for. The alerts page
     * stores both the switches and the kinds in the active profile, so choosing
     * a kind there opens this gate. While it is false the schedule does no
     * lookup at all.
     */
    val proximityAlerts: ProximityAlertHost by lazy {
        ProximityAlertHost(
            source =
                LibraryProximityPlaces(
                    favourites = { favourites.load() },
                    tracks = { tracks.storedTracks() },
                    pois = { box, kinds ->
                        places.pois(
                            indexName = SearchScreenSession.DEFAULT_INDEX,
                            bbox = box,
                            categories = kinds,
                            limit = PROXIMITY_POI_LIMIT,
                            cancellation = CancellationHandle.None,
                        )
                    },
                    log = { message -> Log.i(PROXIMITY_ALERTS_TAG, message) },
                ),
            poiKinds = { alertSettings.current().poiKinds },
            canAlert = { proximityAlertsPossible(alerts.settings.value, alertSettings.current().poiKinds) },
            sink =
                ProximityAlertSink { cue ->
                    alerts.onPlaceAlert(
                        placeKind = cue.place.kind,
                        fixTimeMillis = cue.fixTimeMillis,
                        distanceMeters = cue.distanceMeters,
                        placeName = cue.place.name,
                    )
                },
            log = { message -> Log.i(PROXIMITY_ALERTS_TAG, message) },
        ).also { host ->
            processScope.launch {
                navigation.state.collect { state -> host.onState(state.toProximityState()) }
            }
        }
    }

    /**
     * The one path that starts guidance (T-0413): it points the tone source and
     * both alert hosts at the profile, returns the camera to the profile
     * defaults and resets the alert caches before the session, then starts it.
     * The planner's Start, the follow-track Start and `core.nav.start` all call
     * it; see [GuidanceStarter].
     */
    internal val guidance: GuidanceStarter by lazy {
        GuidanceStarter(navigation, { beeps }, { wayAlerts }, { proximityAlerts }, { navigationCamera })
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

    /** Whether the user allowed online place search. */
    val onlineSearchEnabled: Boolean
        get() = privacySwitches.state(NetworkSwitches.onlineSearch).enabled

    /** Turns the online-search privacy switch on or off. */
    suspend fun setOnlineSearchEnabled(enabled: Boolean): Outcome<Unit> = privacySwitches.setEnabled(NetworkSwitches.onlineSearch, enabled)

    /** The current privacy switch list, for a screen that reads it once. */
    fun privacySwitchStates(): List<SwitchState> = privacySwitches.states()

    /** The switch list whenever a permission changes. */
    val privacyStates: Flow<List<SwitchState>>
        get() = privacySwitches.changes

    /** Whether the user allowed online raster tiles. */
    val onlineTilesEnabled: Boolean
        get() = privacySwitches.state(NetworkSwitches.onlineTiles).enabled

    /** Turns the online-tiles privacy switch on or off. */
    suspend fun setOnlineTilesEnabled(enabled: Boolean): Outcome<Unit> = privacySwitches.setEnabled(NetworkSwitches.onlineTiles, enabled)

    /** Whether the user allowed region downloads and update checks. */
    val regionDownloadsEnabled: Boolean
        get() = privacySwitches.state(NetworkSwitches.regionUpdates).enabled

    /** Turns the region-download privacy switch on or off. */
    suspend fun setRegionDownloadsEnabled(enabled: Boolean): Outcome<Unit> =
        privacySwitches.setEnabled(NetworkSwitches.regionUpdates, enabled)

    /**
     * Validates and probes a user URL template through the gate, then makes it
     * the active source. The map screen observes [RasterLayerSettings.active]
     * and swaps the layer. A failed probe keeps the previous source.
     */
    suspend fun useCustomRasterSource(template: String): Outcome<RasterSource> =
        rasterSettings.useCustom(template) { source -> rasterTiles.probe(source) }

    /**
     * Moves the online raster layer under the base map or over it, and stores
     * the choice. The map screen observes [RasterLayerSettings.active] and
     * moves the same layer, so no tile is fetched again.
     */
    fun setRasterPlacement(placement: RasterPlacement): Outcome<Unit> = rasterSettings.setPlacement(placement)

    /**
     * Sets the layer's transparency in whole percent. [store] writes it to the
     * settings file, which a drag does once rather than on every step.
     */
    fun setRasterTransparency(
        percent: Int,
        store: Boolean,
    ): Outcome<Unit> = rasterSettings.setTransparencyPercent(percent, store)

    /**
     * The one query path (T-0203, T-0212): offline rows first, then the opt-in
     * online rows from the Photon provider, merged in [MergedPlaceSearch].
     */
    private val mergedSearch: MergedPlaceSearch by lazy {
        val indexes = places
        MergedPlaceSearch(
            offline = { request ->
                indexes.search(
                    indexName = request.index,
                    query = request.query,
                    context = request.context,
                    limit = request.limit,
                    cancellation = request.cancellation,
                )
            },
            online = { request ->
                when (
                    val invoked =
                        providerRegistry.invoke(ONLINE_SEARCH_PLUGIN, PlaceProviderContract.REFERENCE) { provider ->
                            val placeProvider = provider as? PlaceProvider
                            if (placeProvider == null) {
                                Outcome.Failure(AppError.Unsupported)
                            } else {
                                placeProvider.search(
                                    query = request.query,
                                    context = request.context,
                                    limit = request.limit,
                                    cancellation = request.cancellation,
                                )
                            }
                        }
                ) {
                    is Outcome.Success -> invoked.value
                    is Outcome.Failure -> invoked
                }
            },
            onlineEnabled = { onlineSearchEnabled },
            positionAllowed = { onlineSearchSettings.isPositionAllowed() },
        )
    }

    /**
     * The search screen's state (T-0203). The shell sends each keystroke through
     * the command bus; this session holds the one query path and publishes the
     * result set both the list and the map markers draw.
     */
    val searchScreen: SearchScreenSession by lazy {
        SearchScreenSession { request -> mergedSearch.search(request) }
    }

    /**
     * The track library over the generated `file.gpx` provider (T-0401). Under
     * [tracksRootProvider] the store persists; without a root it is memory-only.
     * A stripped height is filled from the mounted DEM when there is one
     * (T-0403).
     */
    val tracks: TrackAccess by lazy {
        TrackAccess(
            providerRegistry,
            GeneratedPlugins.providers,
            tracksRoot = tracksRootProvider(),
            elevation = mountedElevation,
        )
    }

    /**
     * One track or a selected list as a GPX file for the save or share path
     * (T-0410). The track library UI (T-0414) is its caller; no screen exists
     * yet.
     */
    val trackExport: TrackExport by lazy {
        TrackExport(tracks, platformActions)
    }

    /**
     * The share-sheet import and its notice (T-0416). The activity hands it the
     * URI another app sent; it imports through the same [tracks] the library
     * uses, so a duplicate file updates the record its hash already names.
     */
    internal val trackShare: TrackShareImport by lazy {
        TrackShareImport(tracks = { tracks })
    }

    /**
     * The map layer over the track library (T-0401). The plugin module owns the
     * controller; the app gives it the store's drawable list and the bound map.
     */
    val trackLayer: TrackLayer by lazy {
        TrackLayer(
            tracks = tracks.drawn,
            host = { mapSession.hostOrNull() },
            log = { message -> Log.w(TRACK_LAYER_TAG, message) },
        )
    }

    /**
     * The map layer over the planned route (T-0408). The plugin module owns the
     * colour and pattern decision; the app gives it the bound map and the route
     * the map screen already draws.
     */
    val routeLayer: RouteLayer by lazy {
        RouteLayer(
            host = { mapSession.hostOrNull() },
            log = { message -> Log.w(ROUTE_LAYER_TAG, message) },
        )
    }

    /**
     * The track library's folder definitions (T-0414), in one small index
     * beside the track documents. Without a tracks root the folders live in
     * memory, exactly like the tracks themselves.
     */
    val trackFolders: TrackFolderStore by lazy {
        TrackFolderStore(
            file = tracksRootProvider()?.let { root -> File(root, TRACK_LIBRARY_INDEX) },
        )
    }

    /**
     * The library screen's access over the track store and the folder index
     * (T-0414). It is the only writer the `track.library` plugin gets, so the
     * sidecars and the folder index always match the screen state.
     */
    val trackLibrary: StoreTrackLibrary by lazy { StoreTrackLibrary({ tracks }, trackFolders) }

    /**
     * The favourites library under the app's private files root (T-0201).
     *
     * One instance is cached so every writer and every reader share the store's
     * mutex and its parsed snapshot; a backup restore replaces the library
     * through this store, so nothing has to drop the cache.
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

    /**
     * The v1 privacy switches. Region downloads name
     * [nl.r1a.grassmap.core.services.NetworkSwitches.regionUpdates]; the real
     * gate refuses the request while the switch is off.
     */
    private val privacySwitches: PrivacySwitches = PrivacySwitchSettings()
    private val networkGate: NetworkGate = PrivacyNetworkGate(privacySwitches)

    /** The production network path for region downloads. */
    val regionNetwork: NetworkAccess = GateNetworkAccess(networkGate)

    /**
     * The region store root, or null when the app has no files directory. The
     * first access sweeps the versions an earlier update superseded, before any
     * mount or install in this process (T-0507).
     */
    val regionsRoot: File? by lazy { regionsRootProvider()?.also(SupersededVersions::sweep) }

    /** The merged routing view over the installed packs (T-0518), or null without a store. */
    private val mountedRoutingPacks: MountedRoutingPacks? by lazy {
        regionsRoot?.let { root -> MountedRoutingPacks(root) { message -> Log.i(ROUTING_MOUNT_TAG, message) } }
    }

    /**
     * The region store behind the downloads screen (T-0504). It shares the
     * app-private root and the gated network path with the downloader below.
     * Its worker fetches nothing until the screen asks for the catalogue, and
     * every request names `network.region_updates`.
     */
    val regionStore: RegionStore? by lazy {
        regionsRoot?.let { root ->
            val downloadsSettings = DownloadsSettings(downloadsSettingsFileProvider())
            RegionStore(
                paths = RegionPaths(root),
                policy = DownloadPolicy(switch = NetworkSwitches.regionUpdates),
                network = regionNetwork,
                scope = processScope,
                transport = HttpDownloadTransport(),
                log = { message -> Log.i(REGIONS_TAG, message) },
                mountedByReaders = { mountedBaseline },
                metered = meteredNetwork,
                initialMobileDataPolicy = downloadsSettings.load(),
                onMobileDataPolicyChanged = { policy ->
                    downloadsSettings.save(policy)
                    applyAutoUpdateSchedule()
                },
                initialAutoUpdate = downloadsSettings.loadAutoUpdate(),
                onAutoUpdateChanged = { enabled ->
                    downloadsSettings.saveAutoUpdate(enabled)
                    applyAutoUpdateSchedule()
                    if (!enabled) {
                        autoUpdater?.cancelStarted()
                    }
                },
            ).also { store -> keepServiceInStep(store) }
        }
    }

    /**
     * The monthly update over [regionStore] (T-0508). Its check goes through
     * the same privacy gate as every region request and runs only while the
     * `network.region_updates` switch is on.
     */
    val autoUpdater: AutoUpdater? by lazy {
        regionStore?.let { store ->
            AutoUpdater(
                access = store,
                ledger = FileAutoUpdateLedger(autoUpdateLedgerFileProvider()),
                clock = autoUpdateClock,
                isSwitchOn = { regionDownloadsEnabled },
                notifier = autoUpdateNotifier,
                log = { message -> Log.i(REGIONS_TAG, message) },
            )
        }
    }

    /**
     * Runs one update window: the check, and for a queued update the wait until
     * it installs or stops for Wi-Fi, then the notice. It returns only then, so
     * the job that calls it keeps the process alive for the whole transfer. A
     * cancelled caller stops the wait, and the next window starts again.
     */
    suspend fun runAutoUpdateWindow(): AutoUpdateRun? {
        val updater = autoUpdater ?: return null
        val run = updater.runIfDue()
        if (run is AutoUpdateRun.Queued) {
            updater.announce(run)
        }
        return run
    }

    /**
     * Brings the system wake-up in line with the stored choices. The app calls
     * it at start, and the store calls it when a choice changes. Call it off Main.
     */
    fun applyAutoUpdateSchedule() {
        val settings = DownloadsSettings(downloadsSettingsFileProvider())
        autoUpdateScheduler.apply(
            enabled = settings.loadAutoUpdate(),
            wifiOnly = settings.load() == MobileDataPolicy.WIFI_ONLY,
        )
    }

    /** Runs the download service exactly while a transfer is in flight (T-0505). */
    private fun keepServiceInStep(store: RegionStore) {
        processScope.launch {
            store.data
                .map { it.transfers.isNotEmpty() }
                .distinctUntilChanged()
                .collect { active -> if (active) downloadService.start() else downloadService.stop() }
        }
    }

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
            routeCommands(mapSession, routingEngine, { dataSources }) +
            navCommands(mapSession, routingEngine, navigation, guidance, { beeps }, { alerts }) +
            searchCommands { places } +
            searchScreenCommands(searchScreen, searchHistory) { message -> Log.i(SEARCH_TAG, message) } +
            searchHistoryCommands(searchHistory) +
            trackCommands({ tracks }, assetReader) +
            favouriteCommands({ favourites }, assetReader).map { command ->
                observedFavouriteCommand(command) { favouritesBackup?.onMutation() }
            } +
            regionCommands({ regionDownloader }, regionNetwork, debuggable) +
            rasterCommands(
                settings = { rasterSettings },
                enableOnlineTiles = ::setOnlineTilesEnabled,
                probe = { source -> rasterTiles.probe(source) },
                debuggable = debuggable,
            ) +
            UnitsCommand({ unitSettings }) +
            SearchHistorySettingCommand({ searchHistorySettings }) +
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

    /**
     * The log sink plugins report through (T-0409), so a plugin outcome lands
     * beside the app's own lines under the same platform log.
     */
    private val pluginLogger: Logger =
        object : Logger {
            override fun log(
                level: LogLevel,
                tag: String,
                message: String,
            ) {
                val priority =
                    when (level) {
                        LogLevel.DEBUG -> Log.DEBUG
                        LogLevel.INFO -> Log.INFO
                        LogLevel.WARN -> Log.WARN
                        LogLevel.ERROR -> Log.ERROR
                    }
                Log.println(priority, "GrassMaps/$tag", message)
            }
        }

    val pluginHost: PluginHost by lazy {
        PluginHost(
            plugins = GeneratedPlugins.plugins,
            streams = GeneratedStreams.streams,
            commands = GeneratedPlugins.commands,
            hostCommands = hostCommands,
            dispatcher = dispatchers.default,
            locationService = locationSource,
            networkGate = networkGate,
            cameraFlow = mapSession.camera,
            devToolsAccess = devTools,
            favouritesListsAccess = favouritesLists,
            trackLibraryAccess = trackLibrary,
            platformActions = platformActions,
            routingPacks = {
                mountedRoutingPacks?.packDirectory()
                    ?: routingFilesProvider()?.let { root ->
                        File(root, ROUTING_PACK_DIRECTORY).absolutePath
                    }
            },
            routingEngines = routingEngineAccess,
            routeHistoryAccess = routeHistory,
            routingOptionsAccess = RoutingOptionsAccess { profile -> routingOptionsSettings.forProfile(profile) },
            regionDownloadsAccess = regionStore ?: NoRegionDownloadsAccess,
            elevationAccess = elevationAccess,
            logger = pluginLogger,
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
        DataSourceAccess(providerRegistry, dataSourcePluginId)
    }

    /** The contour overlay provider; the map adds and removes its layers. */
    val contours: ContoursAccess by lazy {
        ContoursAccess(providerRegistry)
    }

    private companion object {
        const val SEARCH_TAG = "GrassMapsSearch"
        const val REGIONS_TAG = "GrassMapsRegions"
        const val MAP_CONTROLS_TAG = "GrassMapsMapControls"
        const val LINKS_TAG = "GrassMapsLinks"
        const val FAVOURITES_TAG = "GrassMapsFavourites"
        const val NAVIGATION_TAG = "GrassMapsNavigation"
        const val WAY_ALERTS_TAG = "GrassMapsWayAlerts"
        const val PROXIMITY_ALERTS_TAG = "GrassMapsProximityAlerts"
        const val ALERTS_TAG = "GrassMapsAlerts"
        const val TRACK_LAYER_TAG = "GrassMapsTracks"
        const val ROUTE_LAYER_TAG = "GrassMapsRoute"
        const val TRACK_LIBRARY_INDEX = ".library.json"
        const val ROUTING_PACK_DIRECTORY = "valhalla"
        private const val ROUTING_MOUNT_TAG = "RoutingMount"

        /** The most places one proximity lookup asks the index for (T-0413). */
        const val PROXIMITY_POI_LIMIT = 50

        val ROUTING_ENGINE_PLUGIN = PluginId("valhalla")
        val ONLINE_SEARCH_PLUGIN = PluginId("online.search")
    }
}
