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

/** Starts and stops [DownloadService] in this app's process. */
class AndroidDownloadServiceHandle(
    private val context: Context,
    private val log: (String) -> Unit = {},
) : DownloadServiceHandle {
    override fun start() {
        val intent = Intent(context, DownloadService::class.java)
        try {
            context.startForegroundService(intent)
        } catch (error: IllegalStateException) {
            // Android 12+ refuses a start from the background. The transfer
            // still runs in the process, only without the notification.
            log("download-service-refused detail=${error.javaClass.simpleName}")
        } catch (error: SecurityException) {
            log("download-service-refused reason=permission")
        }
    }

    override fun stop() {
        context.stopService(Intent(context, DownloadService::class.java))
    }
}
