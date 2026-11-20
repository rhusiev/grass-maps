package nl.r1a.grassmap.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import nl.r1a.grassmap.regions.AutoUpdateNotice
import nl.r1a.grassmap.regions.AutoUpdateNotifier

/**
 * Posts the notice for the monthly map update (T-0508). The text says whether
 * the new maps are installed or wait for Wi-Fi, and that elevation uses them
 * at the next start. On Android 13 and later the post is silently dropped
 * while the user has not granted the notification permission.
 */
class AndroidAutoUpdateNotifier(
    private val context: Context,
) : AutoUpdateNotifier {
    override fun notify(notice: AutoUpdateNotice) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (notice is AutoUpdateNotice.Withdrawn) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        createChannel(manager)
        val maps = notice.maps
        val title =
            when (notice) {
                is AutoUpdateNotice.Installed -> context.resources.getQuantityString(R.plurals.map_update_installed, maps, maps)
                is AutoUpdateNotice.WaitingForWifi -> context.resources.getQuantityString(R.plurals.map_update_waiting, maps, maps)
                AutoUpdateNotice.Withdrawn -> return
            }
        val notification =
            Notification
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(context.getString(R.string.map_update_applies_at_next_start))
                .setContentIntent(openIntent())
                .setAutoCancel(true)
                .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (error: SecurityException) {
            Log.w(TAG, "map-update-notice-refused reason=permission")
        }
    }

    private fun openIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, OPEN_REQUEST, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun createChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) {
            return
        }
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.map_update_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.map_update_channel_description)
                setShowBadge(false)
            }
        manager.createNotificationChannel(channel)
    }

    private companion object {
        const val TAG = "AutoUpdate"
        const val CHANNEL_ID = "map_updates"
        const val NOTIFICATION_ID = 4508
        const val OPEN_REQUEST = 3
    }
}
