package nl.r1a.grassmap.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.StrictMode
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.core.services.AndroidLocationSource
import nl.r1a.grassmap.core.services.MockGpxLocationSource
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.service.LocationService
import java.io.IOException

/**
 * Debug-only StrictMode policy and the GPX replay.
 *
 * The CI emulator job fails when the app logs a `StrictMode policy violation`
 * while starting. `Log.i` below is the marker the job checks for, so an APK
 * without this policy cannot pass the check by staying silent.
 *
 * The debug manifest replaces the main application name, so this class extends
 * [GrassMapsApplication] to keep the process-scoped container and its startup
 * path. The GPX replay is the default location source; launching the activity
 * with `--ez t0103_platform_location true` installs the real platform source
 * instead, which is how the permission flow is exercised on the emulator. A
 * release build has no replay and the fixture asset is not packaged.
 */
class DebugApplication : GrassMapsApplication() {
    private val platformLocation = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) {
                    platformLocation.value =
                        activity.intent?.getBooleanExtra(EXTRA_PLATFORM_LOCATION, false) == true
                }

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
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

    override fun extraCommands(): (MapSessionHolder) -> List<Command<*, *>> = { session -> listOf(SetCameraCommand(session)) }

    /** Debug builds treat the developer as the AI-control switch holder. */
    override fun aiControlEnabled(): Boolean = true

    override fun locationSource(): LocationService =
        DebugLocationSource(
            platform = AndroidLocationSource(this),
            replay =
                LoggingLocationSource(
                    MockGpxLocationSource.fromLoader(
                        loadGpx = { readFixture() },
                        loop = true,
                    ),
                ),
            usePlatform = platformLocation,
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
        const val EXTRA_PLATFORM_LOCATION = "t0103_platform_location"
    }
}
