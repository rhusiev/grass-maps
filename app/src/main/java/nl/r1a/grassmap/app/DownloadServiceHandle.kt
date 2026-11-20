package nl.r1a.grassmap.app

import android.content.Context
import android.content.Intent

/**
 * The app's path to the download foreground service (T-0505). It is an
 * interface so [AppContainer] stays free of Android types and a test can see
 * the starts and stops without a device.
 */
interface DownloadServiceHandle {
    /** Starts the service and its notification. Starting twice is harmless. */
    fun start()

    /** Stops the service and removes its notification. */
    fun stop()
}

/** The handle for a build or a test that cannot host a foreground service. */
object NoDownloadServiceHandle : DownloadServiceHandle {
    override fun start() = Unit

    override fun stop() = Unit
}

/**
 * Starts and stops [DownloadService] in this app's process.
 *
 * A refused start is logged and the transfer continues without a
 * notification. A stop that arrives while the start is pending is recorded,
 * so it cannot destroy the service before its `startForeground` call.
 */
class AndroidDownloadServiceHandle internal constructor(
    private val startService: () -> Unit,
    private val stopService: () -> Unit,
    private val gate: ForegroundStartGate,
    private val log: (String) -> Unit = {},
) : DownloadServiceHandle {
    constructor(context: Context, log: (String) -> Unit = {}) : this(
        startService = { context.startForegroundService(Intent(context, DownloadService::class.java)) },
        stopService = { context.stopService(Intent(context, DownloadService::class.java)) },
        gate = DownloadService.startGate,
        log = log,
    )

    override fun start() {
        gate.starting()
        try {
            startService()
        } catch (error: IllegalStateException) {
            gate.reached()
            // Android 12+ refuses a start from the background. The transfer
            // still runs in the process, only without the notification.
            log("download-service-refused detail=${error.javaClass.simpleName}")
        } catch (error: SecurityException) {
            gate.reached()
            log("download-service-refused reason=permission")
        } catch (error: RuntimeException) {
            gate.reached()
            throw error
        }
    }

    override fun stop() {
        if (!gate.deferStopIfStarting()) {
            stopService()
        }
    }
}
