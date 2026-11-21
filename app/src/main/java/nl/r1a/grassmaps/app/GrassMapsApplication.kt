package nl.r1a.grassmaps.app

import android.app.Activity
import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Debug
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.r1a.grassmaps.alerts.AndroidAlertEffects
import nl.r1a.grassmaps.alerts.AndroidBeepSink
import nl.r1a.grassmaps.core.services.AndroidHeadingSource
import nl.r1a.grassmaps.core.services.AndroidLocationSource
import nl.r1a.grassmaps.core.services.AndroidStrings
import nl.r1a.grassmaps.core.services.DeviceInfo
import nl.r1a.grassmaps.core.services.FileLocationPermissionStore
import nl.r1a.grassmaps.core.services.MockGpxLocationSource
import nl.r1a.grassmaps.core.services.installCrashGuard
import nl.r1a.grassmaps.map.DistanceByTapSetting
import nl.r1a.grassmaps.plugin.api.command.Command
import nl.r1a.grassmaps.plugin.api.contract.PluginId
import nl.r1a.grassmaps.plugin.api.contract.SwitchId
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.service.LocationService
import nl.r1a.grassmaps.regions.MeteredNetworkMonitor
import nl.r1a.grassmaps.regions.NeverMetered
import nl.r1a.grassmaps.tracks.TrackTrash
import java.io.File
import java.io.IOException

/**
 * Application entry point. The container is process-scoped and built lazily;
 * only the first activation event pays for the plugin host.
 */
open class GrassMapsApplication : Application() {
    /** Application-lifetime work that must not hold the Main thread. */
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val container: AppContainer by lazy {
        val location = locationSource()
        val documentSaver = DocumentSaveCoordinator(ContentResolverDocumentWriter(this))
        AppContainer(
            strings = AndroidStrings(this),
            locationSource = location,
            locationPermissionStore =
                FileLocationPermissionStore {
                    File(filesDir, "$PERMISSION_DIRECTORY/$PERMISSION_FILE")
                },
            routingFilesProvider = { getExternalFilesDir(null) ?: filesDir },
            searchFilesProvider = { getExternalFilesDir(null) ?: filesDir },
            assetReader = { path -> readAsset(path) },
            regionsRootProvider = { File(filesDir, UserDataLayout.REGIONS_DIRECTORY) },
            tracksRootProvider = { File(filesDir, UserDataLayout.TRACKS_DIRECTORY) },
            diagnosticsRootProvider = { File(filesDir, DIAGNOSTICS_DIRECTORY) },
            deviceInfoProvider = { deviceInfo() },
            debuggable = { isDebuggable() },
            extraCommands = extraCommands(),
            devTools = devToolsAccess(location),
            devToolsPanel = devToolsPanel(),
            dataSourcePluginId = dataSourcePluginId(),
            aiControlEnabled = { aiControlEnabled() },
            switchEnabled = switchEnabled(),
            distanceByTap = distanceByTap(),
            headingSource = AndroidHeadingSource(this, compassScope),
            unitSettingsFileProvider = { File(filesDir, UNIT_SETTINGS_PATH) },
            searchHistoryFileProvider = { File(filesDir, UserDataLayout.SEARCH_HISTORY_PATH) },
            searchHistorySettingsFileProvider = { File(filesDir, SEARCH_HISTORY_SETTINGS_PATH) },
            onlineSearchSettingsFileProvider = { File(filesDir, ONLINE_SEARCH_SETTINGS_PATH) },
            contoursSettingsFileProvider = { File(filesDir, CONTOURS_SETTINGS_PATH) },
            pluginEnabledFileProvider = { File(filesDir, PLUGIN_ENABLED_PATH) },
            rasterSettingsFileProvider = { File(filesDir, RASTER_SETTINGS_PATH) },
            mapStyleSettingsFileProvider = { File(filesDir, MAP_STYLE_SETTINGS_PATH) },
            mapLabelSettingsFileProvider = { File(filesDir, MAP_LABEL_SETTINGS_PATH) },
            routeHistoryFileProvider = { File(filesDir, UserDataLayout.ROUTE_HISTORY_PATH) },
            userDataRootProvider = { filesDir },
            favouritesRootProvider = { filesDir },
            platformActions = AndroidPlatformActions(this, documentSaver),
            documentSaver = documentSaver,
            navigationService = AndroidNavigationServiceHandle(this),
            downloadService = AndroidDownloadServiceHandle(this) { message -> Log.w("DownloadService", message) },
            downloadsSettingsFileProvider = { File(filesDir, DOWNLOADS_SETTINGS_PATH) },
            meteredNetwork = meteredNetworkMonitor(),
            autoUpdateLedgerFileProvider = { File(filesDir, AUTO_UPDATE_LEDGER_PATH) },
            autoUpdateScheduler = AndroidAutoUpdateScheduler(this),
            autoUpdateNotifier = AndroidAutoUpdateNotifier(this),
            beepSink = AndroidBeepSink(this),
            alertEffectSink = AndroidAlertEffects(this),
            defaultRegionToken = defaultRegionToken(),
        )
    }

    private fun meteredNetworkMonitor(): MeteredNetworkMonitor {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return NeverMetered
        return AndroidMeteredNetworkMonitor(manager).also { it.register() }
    }

    override fun onCreate() {
        super.onCreate()
        installCrashGuard { container.diagnostics?.crashGuard }
        // A deleted track is undoable for seven days. The purge runs once per
        // process start, here: the park directory needs no store, so no plugin
        // host is built for it, and the IO dispatcher keeps the first screen
        // from waiting on it.
        maintenanceScope.launch { container.applyAutoUpdateSchedule() }
        maintenanceScope.launch {
            container.profileCatalog.load()
            container.widgetLayoutStore.load()
            container.alertSettings.ensureLoaded()
            // Publish a full snapshot once every store has read its file, so the
            // first composition does not see a default widget layout.
            container.activeProfile.activate(container.activeProfile.activeProfileId())
        }
        maintenanceScope.launch {
            TrackTrash.purge(
                File(filesDir, UserDataLayout.TRACKS_DIRECTORY),
                System.currentTimeMillis(),
            )
        }
    }

    /** The device facts a bundle carries; no identifier is included. */
    private fun deviceInfo(): DeviceInfo =
        DeviceInfo(
            model =
                listOf(Build.MANUFACTURER, Build.MODEL)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(" "),
            osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            appVersion = packageVersionName(),
        )

    private fun packageVersionName(): String =
        try {
            this.packageManager.getPackageInfo(this.packageName, 0).versionName ?: UNKNOWN_VERSION
        } catch (exception: PackageManager.NameNotFoundException) {
            UNKNOWN_VERSION
        }

    /** The debug-only seams check this flag; the manifest sets it for debug builds only. */
    private fun isDebuggable(): Boolean = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Reads a debug asset for the control channel. It runs off Main, in the
     * command's IO dispatcher, and a missing asset is a null value.
     */
    private fun readAsset(path: String): ByteArray? =
        try {
            assets.open(path).use { stream -> stream.readBytes() }
        } catch (exception: IOException) {
            null
        } catch (exception: SecurityException) {
            null
        }

    /** The region-server token this build embeds. Only the debug build has one. */
    internal open fun defaultRegionToken(): String = ""

    /** The measurement harness for this build; the release app observes nothing. */
    internal open fun mapHarness(activity: Activity): MapHarness = NoMapHarness

    /**
     * Commands that exist only in a debug build. The release app contributes an
     * empty list, so no unrestricted camera command is compiled into it.
     */
    internal open fun extraCommands(): (MapSessionHolder) -> List<Command<*, *>> = { emptyList() }

    /**
     * The distance-by-tap mode for this build. The release app keeps the
     * two-finger tap on zoom out until T-0016 binds the setting to the shared
     * settings service; a debug build can switch it from the launch intent.
     */
    internal open fun distanceByTap(): DistanceByTapSetting = DistanceByTapSetting { false }

    /**
     * The location provider for this build. A release build starts on the
     * platform source and can switch to the committed GPX replay through the
     * development-tools plugin. A debug build overrides this with the same
     * replay, active by default.
     */
    internal open fun locationSource(): LocationService = replayLocation

    /**
     * The development-tools overlay. A release build contributes none, so the
     * plugin module and its panel code exist only in a debug build.
     */
    internal open fun devToolsPanel(): (@Composable (Modifier) -> Unit)? = null

    /**
     * The synthetic DataSource plugin. A debug build declares it; release
     * returns null because the plugin is not packaged there.
     */
    internal open fun dataSourcePluginId(): PluginId? = null

    /**
     * The AI-control switch. Release stays off: the control channel and the
     * development-tools plugin are debug-only, so a release build has no code
     * that can turn this on.
     */
    internal open fun aiControlEnabled(): Boolean = false

    /**
     * The switches this build can turn on. A debug build enables the
     * development-tools switch; release leaves every switch off.
     */
    internal open fun switchEnabled(): (SwitchId) -> Boolean = { false }

    /** Development seams over whichever replay source this build installed. */
    internal fun devToolsAccess(location: LocationService): DevToolsRuntime =
        (location as? ReplayControl)?.let { replay ->
            AppDevTools(replay, runtimeMetrics)
        } ?: NoDevToolsRuntime

    private val replayLocation: ReplayLocationSource by lazy {
        ReplayLocationSource(
            platform = AndroidLocationSource(this),
            replay = {
                MockGpxLocationSource.fromLoader(
                    loadGpx = { readReplayGpx() },
                    loop = true,
                )
            },
        )
    }

    private val runtimeMetrics: RuntimeMetrics by lazy {
        RuntimeMetrics(memoryBytes = ::appMemoryBytes)
    }

    /** The compass outlives an Activity; the map only subscribes while visible. */
    private val compassScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Total PSS of this process, the figure the panel shows as memory. */
    private fun appMemoryBytes(): Long {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss.toLong() * BYTES_PER_KILOBYTE
    }

    /**
     * Reads the committed replay fixture for the development-tools panel. It
     * runs off Main, in the loader the replay's collector calls, and a missing
     * asset is a null fix rather than a crash.
     */
    private suspend fun readReplayGpx(): Outcome<String> =
        withContext(Dispatchers.IO) {
            try {
                Outcome.Success(
                    assets.open(DEV_TOOLS_GPX_ASSET).bufferedReader().use { it.readText() },
                )
            } catch (exception: IOException) {
                Log.w(TAG, "dev-tools replay missing: ${exception.message}")
                Outcome.Failure(AppError.CorruptData)
            } catch (exception: SecurityException) {
                Log.w(TAG, "dev-tools replay unreadable: ${exception.message}")
                Outcome.Failure(AppError.CorruptData)
            }
        }

    private companion object {
        const val TAG = "GrassMapsDevTools"
        const val DIAGNOSTICS_DIRECTORY = "diagnostics"
        const val PERMISSION_DIRECTORY = "location"
        const val PERMISSION_FILE = "permission-asked"
        const val UNIT_SETTINGS_PATH = "settings/units.json"
        const val SEARCH_HISTORY_SETTINGS_PATH = "settings/search-history.json"
        const val ONLINE_SEARCH_SETTINGS_PATH = "settings/online-search.json"
        const val CONTOURS_SETTINGS_PATH = "settings/contours.json"
        const val PLUGIN_ENABLED_PATH = "settings/plugins.json"
        const val RASTER_SETTINGS_PATH = "settings/raster.json"
        const val MAP_STYLE_SETTINGS_PATH = "settings/style.json"
        const val MAP_LABEL_SETTINGS_PATH = "settings/map-labels.json"
        const val DOWNLOADS_SETTINGS_PATH = "settings/downloads.json"
        const val AUTO_UPDATE_LEDGER_PATH = "settings/auto-update.json"
        const val DEV_TOOLS_GPX_ASSET = "mock-walk.gpx"
        const val BYTES_PER_KILOBYTE = 1024L
        const val UNKNOWN_VERSION = "unknown"
    }
}
