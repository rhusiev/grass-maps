package nl.r1a.grassmap.app

import android.os.StrictMode
import android.util.Log

/**
 * Debug-only StrictMode policy.
 *
 * The CI emulator job fails when the app logs a `StrictMode policy violation`
 * while starting. `Log.i` below is the marker the job checks for, so an APK
 * without this policy cannot pass the check by staying silent.
 *
 * The debug manifest replaces the main application name, so this class extends
 * [GrassMapsApplication] to keep the process-scoped container and its startup
 * path.
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

    private companion object {
        const val TAG = "GrassMapsStrictMode"
    }
}
