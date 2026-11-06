package nl.r1a.grassmap.app

import android.app.Application
import android.os.StrictMode
import android.util.Log

/**
 * Debug-only StrictMode policy.
 *
 * The CI emulator job fails when the app logs a `StrictMode policy violation`
 * while starting. `Log.i` below is the marker the job checks for, so an APK
 * without this policy cannot pass the check by staying silent.
 *
 * If a main-source Application class lands later (for example with the command
 * bus in T-0012), this class must extend it and call super.onCreate() first.
 * The debug manifest replaces `android:name`, so otherwise debug builds would
 * skip that startup code.
 */
class DebugApplication : Application() {
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
    }

    private companion object {
        const val TAG = "GrassMapsStrictMode"
    }
}
