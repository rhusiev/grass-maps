package nl.r1a.grassmap.app

import android.app.Activity
import android.app.Application
import android.content.pm.ApplicationInfo
import nl.r1a.grassmap.core.services.AndroidStrings
import nl.r1a.grassmap.core.services.LocationSource
import nl.r1a.grassmap.core.services.NoLocationSource
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
            routingFilesProvider = { getExternalFilesDir(null) ?: filesDir },
            searchFilesProvider = { getExternalFilesDir(null) ?: filesDir },
            assetReader = { path -> readAsset(path) },
            regionsRootProvider = { File(filesDir, REGIONS_DIRECTORY) },
            debuggable = { isDebuggable() },
        )
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
     * The location provider for this build. The release app has none until
     * T-0103; a debug build installs its GPX replay.
     */
    internal open fun locationSource(): LocationSource = NoLocationSource

    private companion object {
        const val REGIONS_DIRECTORY = "regions"
    }
}
