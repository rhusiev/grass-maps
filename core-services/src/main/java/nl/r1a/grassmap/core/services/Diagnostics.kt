package nl.r1a.grassmap.core.services

import java.io.File

/**
 * The diagnostic services for one process: the log ring, its toggle, the bundle
 * writer and the crash guard.
 *
 * Construction reads the settings file, so build it off the Main thread. The
 * logging path itself only touches the in-memory ring.
 */
class Diagnostics(
    val directory: File,
    capacity: Int = RingLogger.DEFAULT_CAPACITY,
    deviceInfo: () -> DeviceInfo = { DeviceInfo.UNKNOWN },
    clock: () -> Long = System::currentTimeMillis,
    defaultLoggingEnabled: Boolean = false,
) {
    val settings: DiagnosticSettings =
        DiagnosticSettings(
            file = File(directory, SETTINGS_FILE),
            defaultEnabled = defaultLoggingEnabled,
        )

    val logger: RingLogger = RingLogger(capacity = capacity, clock = clock, enabled = settings::isEnabled)

    val bundles: DiagnosticBundleWriter =
        DiagnosticBundleWriter(
            directory = directory,
            records = logger::snapshot,
            evicted = logger::evictedCount,
            deviceInfo = deviceInfo,
            clock = clock,
        )

    val crashGuard: CrashGuard =
        CrashGuard(
            directory = directory,
            records = logger::snapshot,
            deviceInfo = deviceInfo,
            clock = clock,
        )

    fun setLoggingEnabled(enabled: Boolean) = settings.setEnabled(enabled)

    private companion object {
        const val SETTINGS_FILE = "settings.json"
    }
}
