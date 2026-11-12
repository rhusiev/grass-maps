package nl.r1a.grassmap.app

import android.app.Activity
import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.alerts.AndroidAlertEffects
import nl.r1a.grassmap.alerts.AndroidBeepSink
import nl.r1a.grassmap.core.services.AndroidHeadingSource
import nl.r1a.grassmap.core.services.AndroidLocationSource
import nl.r1a.grassmap.core.services.AndroidStrings
import nl.r1a.grassmap.core.services.DeviceInfo
import nl.r1a.grassmap.core.services.FileLocationPermissionStore
import nl.r1a.grassmap.core.services.MockGpxLocationSource
import nl.r1a.grassmap.core.services.installCrashGuard
import nl.r1a.grassmap.map.DistanceByTapSetting
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.service.LocationService
import java.io.File
import java.io.IOException

/**
 * Application entry point. The container is process-scoped and built lazily;
 * only the first activation event pays for the plugin host.
 */
open class GrassMapsApplication : Application() {
    val container: AppContainer by lazy {
        val location = locationSource()
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
            regionsRootProvider = { File(filesDir, REGIONS_DIRECTORY) },
            tracksRootProvider = { File(filesDir, TRACKS_DIRECTORY) },
            diagnosticsRootProvider = { File(filesDir, DIAGNOSTICS_DIRECTORY) },
            deviceInfoProvider = { deviceInfo() },
            debuggable = { isDebuggable() },
            extraCommands = extraCommands(),
            devTools = devToolsAccess(location),
            aiControlEnabled = { aiControlEnabled() },
            distanceByTap = distanceByTap(),
            headingSource = AndroidHeadingSource(this, compassScope),
            unitSettingsFileProvider = { File(filesDir, UNIT_SETTINGS_PATH) },
            searchHistoryFileProvider = { File(filesDir, SEARCH_HISTORY_PATH) },
            searchHistorySettingsFileProvider = { File(filesDir, SEARCH_HISTORY_SETTINGS_PATH) },
            onlineSearchSettingsFileProvider = { File(filesDir, ONLINE_SEARCH_SETTINGS_PATH) },
            contoursSettingsFileProvider = { File(filesDir, CONTOURS_SETTINGS_PATH) },
            routeHistoryFileProvider = { File(filesDir, ROUTE_HISTORY_PATH) },
            routingOptionsFileProvider = { File(filesDir, ROUTING_OPTIONS_SETTINGS_PATH) },
            favouritesRootProvider = { filesDir },
            platformActions = AndroidPlatformActions(this),
            navigationService = AndroidNavigationServiceHandle(this),
            beepSink = AndroidBeepSink(this),
            alertEffectSink = AndroidAlertEffects(this),
        )
    }

    override fun onCreate() {
        super.onCreate()
        installCrashGuard { container.diagnostics?.crashGuard }
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

    /** The AI-control switch. Release stays off until T-0610 wires the switch. */
    internal open fun aiControlEnabled(): Boolean = false

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
     * Reads the committed replay fixture for the release panel. It runs off
     * Main, in the loader the replay's collector calls, and a missing asset is
     * a null fix rather than a crash.
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
        const val REGIONS_DIRECTORY = "regions"
        const val TRACKS_DIRECTORY = "tracks"
        const val DIAGNOSTICS_DIRECTORY = "diagnostics"
        const val PERMISSION_DIRECTORY = "location"
        const val PERMISSION_FILE = "permission-asked"
        const val UNIT_SETTINGS_PATH = "settings/units.json"
        const val SEARCH_HISTORY_SETTINGS_PATH = "settings/search-history.json"
        const val ONLINE_SEARCH_SETTINGS_PATH = "settings/online-search.json"
        const val CONTOURS_SETTINGS_PATH = "settings/contours.json"
        const val ROUTING_OPTIONS_SETTINGS_PATH = "settings/routing-options.json"
        const val SEARCH_HISTORY_PATH = "history/search-history.json"
        const val ROUTE_HISTORY_PATH = "history/route-history.json"
        const val DEV_TOOLS_GPX_ASSET = "mock-walk.gpx"
        const val BYTES_PER_KILOBYTE = 1024L
        const val UNKNOWN_VERSION = "unknown"
    }
}
