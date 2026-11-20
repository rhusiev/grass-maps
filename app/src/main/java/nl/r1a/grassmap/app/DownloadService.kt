package nl.r1a.grassmap.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The dataSync foreground service that keeps region downloads alive while the
 * app is not in the foreground (T-0505).
 *
 * Why a foreground service and not WorkManager: it is the pattern the
 * navigation service already uses, it adds no dependency, and it needs no
 * Google component, which F-Droid builds require. The transfer itself still
 * runs in the region store's worker; the service only keeps the process alive
 * and shows [downloadNotificationContent]. [AppContainer] starts it when a
 * transfer appears and stops it when the list is empty again.
 *
 * `START_NOT_STICKY`: after a process death the region store has no queue to
 * resume, and the partial archive resumes with a Range request when the user
 * starts the download again.
 *
 * The foreground call is the first work of [onCreate], before the store is
 * read. A queue that empties or a stop that arrives while the service is
 * starting must not leave a `startForegroundService` start without its
 * `startForeground` call.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundSafely(notificationFor(null))
        if (!foregroundStarted) {
            startGate.reached()
            return
        }
        val store = (application as GrassMapsApplication).container.regionStore
        if (store == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        scope.launch {
            store.data.collect { data -> show(downloadNotificationContent(data.transfers)) }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_USE_MOBILE_DATA) {
            val store = (application as GrassMapsApplication).container.regionStore
            scope.launch { store?.useMobileDataNow() }
        }
        if (!foregroundStarted) {
            startGate.reached()
            return START_NOT_STICKY
        }
        if (startGate.reached()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun show(content: DownloadNotificationContent?) {
        if (content == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val notification = notificationFor(content)
        if (!foregroundStarted) {
            startForegroundSafely(notification)
            return
        }
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    private fun startForegroundSafely(notification: Notification) {
        val result =
            foregroundStartResult(
                start = {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                },
                log = { message -> Log.w(TAG, message) },
                logPrefix = "download-service",
            )
        if (result == ForegroundStartResult.Started) {
            foregroundStarted = true
            Log.i(TAG, "download-service-start type=dataSync")
        } else {
            stopSelf()
        }
    }

    private fun notificationFor(content: DownloadNotificationContent?): Notification {
        val builder =
            Notification
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(openIntent())
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
        when (content) {
            is DownloadNotificationContent.WaitingForWifi -> {
                builder.setContentTitle(
                    resources.getQuantityString(
                        R.plurals.download_notification_waiting,
                        content.maps,
                        content.maps,
                        content.megabytes,
                    ),
                )
                builder.addAction(useMobileDataAction())
            }

            is DownloadNotificationContent.Downloading -> {
                builder.setContentTitle(
                    resources.getQuantityString(R.plurals.download_notification_downloading, content.maps, content.maps),
                )
                content.percent?.let { builder.setProgress(PERCENT_MAX, it, false) }
            }

            null -> {
                builder.setContentTitle(getString(R.string.download_notification_starting))
            }
        }
        return builder.build()
    }

    private fun useMobileDataAction(): Notification.Action {
        val intent = Intent(this, DownloadService::class.java).setAction(ACTION_USE_MOBILE_DATA)
        return Notification.Action
            .Builder(
                Icon.createWithResource(this, android.R.drawable.stat_sys_download),
                getString(R.string.download_notification_use_mobile_data),
                PendingIntent.getService(
                    this,
                    USE_MOBILE_DATA_REQUEST,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            ).build()
    }

    private fun openIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            OPEN_REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) {
            return
        }
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.download_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.download_notification_channel_description)
                setShowBadge(false)
            }
        manager.createNotificationChannel(channel)
    }

    companion object {
        internal val startGate = ForegroundStartGate()

        const val ACTION_USE_MOBILE_DATA = "nl.r1a.grassmap.app.action.DOWNLOAD_USE_MOBILE_DATA"
        private const val TAG = "DownloadService"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 4505
        private const val USE_MOBILE_DATA_REQUEST = 1
        private const val OPEN_REQUEST = 2
        private const val PERCENT_MAX = 100
    }
}
