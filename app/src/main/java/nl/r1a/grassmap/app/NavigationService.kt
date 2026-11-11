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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.navigation.NavigationState
import nl.r1a.grassmap.plugin.api.service.Strings

/**
 * The location foreground service that keeps guidance alive while the app is
 * not in the foreground.
 *
 * It exists only while a navigation session does. [NavigationController] starts
 * it before the session begins and stops it when the session ends, and the
 * service itself starts nothing: it renders [NavigationController.state] into
 * one ongoing notification and forwards the notification's pause, resume and
 * stop actions to the controller.
 *
 * The service type is `location`, and the manifest declares the matching
 * `FOREGROUND_SERVICE_LOCATION` permission. The app never asks for a background
 * location permission: a running location foreground service counts as the app
 * being in use, so the while-in-use permission covers collection while the
 * activity is gone (user decision Q10, 2026-11-08).
 *
 * `START_NOT_STICKY`: after a process death there is no session to resume, and
 * a restarted service without a route would only hold location for nothing.
 * The notification is public and ongoing so the next turn survives the lock
 * screen.
 */
class NavigationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: NavigationController
    private lateinit var strings: Strings
    private var foregroundStarted = false
    private var lastLoggedContent: String? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as GrassMapsApplication).container
        controller = container.navigation
        strings = container.strings
        createChannel()
        scope.launch {
            controller.state.collect { state -> showState(state) }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundFor(controller.state.value)
                stopIfStranded()
            }

            ACTION_PAUSE -> {
                controller.pause()
                showState(controller.state.value)
            }

            ACTION_RESUME -> {
                controller.resume()
                showState(controller.state.value)
            }

            ACTION_STOP -> {
                controller.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            else -> {
                if (!controller.isActive) {
                    stopSelf(startId)
                } else {
                    startForegroundFor(controller.state.value)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * A start that finds no session shortly after it is a stray start - a
     * duplicate delivered after a stop, or a service started without a route -
     * and must not hold location or the notification forever.
     */
    private fun stopIfStranded() {
        if (controller.isActive) {
            return
        }
        scope.launch {
            delay(STRANDED_START_TIMEOUT_MILLIS)
            if (!controller.isActive) {
                Log.w(TAG, "navigation-service-stopped reason=no-session")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun showState(state: NavigationState) {
        if (state.phase == NavigationPhase.Idle || state.phase == NavigationPhase.Stopped) {
            // The controller is ending or has ended the session and stops the
            // service in the same turn; the last notification stays until then.
            return
        }
        val notification = notificationFor(state)
        logContent(state)
        if (!foregroundStarted) {
            startForegroundFor(state)
            return
        }
        notificationManager()?.notify(NOTIFICATION_ID, notification)
    }

    private fun startForegroundFor(state: NavigationState) {
        if (!startForegroundSafely(notificationFor(state))) {
            return
        }
        foregroundStarted = true
    }

    /**
     * Starts the location type the manifest declares. Android 14 and later
     * refuse a location foreground service without the location permission, so
     * a revocation between the controller's check and this call is caught and
     * ends the session instead of crashing.
     */
    private fun startForegroundSafely(notification: Notification): Boolean =
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            Log.i(TAG, "navigation-service-start type=location")
            true
        } catch (error: SecurityException) {
            Log.w(TAG, "navigation-service-refused reason=permission")
            controller.stop()
            stopSelf()
            false
        }

    private fun notificationFor(state: NavigationState): Notification {
        val content = navigationNotificationContent(state, strings)
        val builder =
            Notification
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(content.title)
                .setContentIntent(openIntent())
                .setCategory(Notification.CATEGORY_NAVIGATION)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
        content.text?.let { builder.setContentText(it) }
        builder.addAction(
            action(
                icon = if (content.paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                title = getString(if (content.paused) R.string.nav_notification_resume else R.string.nav_notification_pause),
                action = if (content.paused) ACTION_RESUME else ACTION_PAUSE,
                requestCode = PAUSE_ACTION_REQUEST,
            ),
        )
        builder.addAction(
            action(
                icon = android.R.drawable.ic_menu_close_clear_cancel,
                title = getString(R.string.nav_notification_stop),
                action = ACTION_STOP,
                requestCode = STOP_ACTION_REQUEST,
            ),
        )
        return builder.build()
    }

    private fun action(
        icon: Int,
        title: String,
        action: String,
        requestCode: Int,
    ): Notification.Action {
        val intent = Intent(this, NavigationService::class.java).setAction(action)
        return Notification.Action
            .Builder(
                Icon.createWithResource(this, icon),
                title,
                PendingIntent.getService(
                    this,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            ).build()
    }

    private fun openIntent(): PendingIntent {
        val intent =
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            OPEN_ACTION_REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel() {
        val manager = notificationManager() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) {
            return
        }
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.nav_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.nav_notification_channel_description)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        manager.createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager? = getSystemService(NotificationManager::class.java)

    /**
     * Logs one line per content change, not per fix. The line carries the turn
     * text and the distance, which is the same evidence the notification shows.
     */
    private fun logContent(state: NavigationState) {
        val content = navigationNotificationContent(state, strings)
        val line =
            "navigation-notification phase=${state.phase} " +
                "title=\"${content.title}\" text=\"${content.text.orEmpty()}\""
        if (line != lastLoggedContent) {
            lastLoggedContent = line
            Log.i(TAG, line)
        }
    }

    companion object {
        const val ACTION_START: String = "nl.r1a.grassmap.action.NAVIGATION_START"
        const val ACTION_PAUSE: String = "nl.r1a.grassmap.action.NAVIGATION_PAUSE"
        const val ACTION_RESUME: String = "nl.r1a.grassmap.action.NAVIGATION_RESUME"
        const val ACTION_STOP: String = "nl.r1a.grassmap.action.NAVIGATION_STOP"

        private const val TAG = "GrassMapsNavService"
        private const val CHANNEL_ID = "navigation"
        private const val NOTIFICATION_ID = 1
        private const val PAUSE_ACTION_REQUEST = 1
        private const val STOP_ACTION_REQUEST = 2
        private const val OPEN_ACTION_REQUEST = 3
        private const val STRANDED_START_TIMEOUT_MILLIS = 5_000L
    }
}
