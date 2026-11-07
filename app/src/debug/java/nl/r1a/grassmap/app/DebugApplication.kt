package nl.r1a.grassmap.app

import android.app.Activity
import android.os.StrictMode
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.core.services.LocationSource
import nl.r1a.grassmap.core.services.MockGpxLocationSource
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.IOException

/**
 * Debug-only StrictMode policy and mock location.
 *
 * The CI emulator job fails when the app logs a `StrictMode policy violation`
 * while starting. `Log.i` below is the marker the job checks for, so an APK
 * without this policy cannot pass the check by staying silent.
 *
 * The debug manifest replaces the main application name, so this class extends
 * [GrassMapsApplication] to keep the process-scoped container and its startup
 * path. The GPX replay is installed here only: a release build has no mock
 * location source and the fixture asset is not packaged.
 */
class DebugApplication : GrassMapsApplication() {
    override fun onCreate() {
        super.onCreate()
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy
                .Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        Log.i(TAG, "StrictMode thread policy enabled")
        DebugControl.start(this, container, DebugControl.settings)
    }

    override fun mapHarness(activity: Activity): MapHarness = DebugMapHarness.from(activity.intent)

    override fun locationSource(): LocationSource =
        LoggingLocationSource(
            MockGpxLocationSource.fromLoader(
                loadGpx = { readFixture() },
                loop = true,
            ),
        )

    private suspend fun readFixture(): Outcome<String> =
        withContext(Dispatchers.IO) {
            try {
                Outcome.Success(
                    assets.open(MOCK_WALK_ASSET).bufferedReader().use { it.readText() },
                )
            } catch (exception: IOException) {
                Log.w(TAG, "mock walk missing: ${exception.message}")
                Outcome.Failure(AppError.CorruptData)
            } catch (exception: SecurityException) {
                Log.w(TAG, "mock walk unreadable: ${exception.message}")
                Outcome.Failure(AppError.CorruptData)
            }
        }

    private companion object {
        const val TAG = "GrassMapsStrictMode"
        const val MOCK_WALK_ASSET = "mock-walk.gpx"
    }
}
