package nl.r1a.grassmap.app

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A message the user can act on after a navigation start is refused (T-0639).
 *
 * The foreground service turns a location-permission refusal on a session that
 * reads real location into this one event. A debug replay needs no location and
 * keeps its silent teardown, so it produces no notice. The event is one-shot: a
 * screen composed later does not replay an old refusal.
 */
internal sealed interface NavigationStartNotice {
    /** The system refused a location foreground service because location is denied. */
    data object LocationPermissionRequired : NavigationStartNotice
}

/** The one notice a refused foreground start can turn into. */
internal fun navigationRefusalNotice(
    result: ForegroundStartResult,
    locationPermissionRequired: Boolean,
): NavigationStartNotice? =
    if (result == ForegroundStartResult.PermissionRefused && locationPermissionRequired) {
        NavigationStartNotice.LocationPermissionRequired
    } else {
        null
    }

/** The data URI Android's app-details settings intent expects. */
internal fun permissionSettingsData(packageName: String): String = "package:$packageName"

/**
 * Carries one-shot navigation notices to the visible activity.
 *
 * The service publishes here instead of touching an activity, because a
 * refusal can arrive while the app is in the background. A bound activity means
 * the map screen can show the message with its settings action; with no bound
 * activity the caller falls back to a notification.
 */
internal class NavigationNoticeHost {
    private val events =
        MutableSharedFlow<NavigationStartNotice>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** The notices for the map screen to show. */
    val notices: SharedFlow<NavigationStartNotice> = events.asSharedFlow()

    @Volatile
    private var owner: Any? = null

    fun bind(owner: Any) {
        this.owner = owner
    }

    fun unbind(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
        }
    }

    /** Returns true when a bound activity can show the notice inside the app. */
    fun publish(notice: NavigationStartNotice): Boolean {
        events.tryEmit(notice)
        return owner != null
    }
}
