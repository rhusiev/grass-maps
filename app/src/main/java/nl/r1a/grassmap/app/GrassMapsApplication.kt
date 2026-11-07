package nl.r1a.grassmap.app

import android.app.Activity
import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import nl.r1a.grassmap.core.services.AndroidLocationSource
import nl.r1a.grassmap.core.services.AndroidStrings
import nl.r1a.grassmap.core.services.DeviceInfo
import nl.r1a.grassmap.core.services.FileLocationPermissionStore
import nl.r1a.grassmap.core.services.installCrashGuard
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.service.LocationService
import java.io.File
import java.io.IOException

/**
 * Application entry point. The container is process-scoped and built lazily;
 * only the first activation event pays for the plugin host.
 */
open class GrassMapsApplication : Application() {
    val container: AppContainer by lazy {
        AppContainer(
            strings = AndroidStrings(this),
            locationSource = locationSource(),
            locationPermissionStore =
                FileLocationPermissionStore {
                    File(filesDir, "$PERMISSION_DIRECTORY/$PERMISSION_FILE")
                },
            routingFilesProvider = { getExternalFilesDir(null) ?: filesDir },
            searchFilesProvider = { getExternalFilesDir(null) ?: filesDir },
            assetReader = { path -> readAsset(path) },
            regionsRootProvider = { File(filesDir, REGIONS_DIRECTORY) },
            diagnosticsRootProvider = { File(filesDir, DIAGNOSTICS_DIRECTORY) },
            deviceInfoProvider = { deviceInfo() },
            debuggable = { isDebuggable() },
            extraCommands = extraCommands(),
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
     * The location provider for this build: the foreground-only platform
     * source. A debug build overrides it with its GPX replay.
     */
    internal open fun locationSource(): LocationService = AndroidLocationSource(this)

    private companion object {
        const val REGIONS_DIRECTORY = "regions"
        const val DIAGNOSTICS_DIRECTORY = "diagnostics"
        const val PERMISSION_DIRECTORY = "location"
        const val PERMISSION_FILE = "permission-asked"
        const val UNKNOWN_VERSION = "unknown"
    }
}
