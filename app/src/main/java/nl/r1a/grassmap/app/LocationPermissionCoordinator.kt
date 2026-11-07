package nl.r1a.grassmap.app

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.core.services.LocationPermissionStore
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.error.onFailure
import nl.r1a.grassmap.plugin.api.service.LocationService

/**
 * The one place that turns a user action into a location permission decision.
 *
 * It never runs on launch: only [ensureAllowed] reaches the system dialog, and
 * a command calls it from the position control. The asked flag is persisted
 * before the dialog opens, so a denied answer is final for this install and
 * the app never asks again on its own. Without a bound activity it refuses
 * with [AppError.NotReady] instead of silently allowing location.
 *
 * A replay source needs no permission, so [ensureAllowed] returns allowed
 * without touching the dialog.
 */
class LocationPermissionCoordinator(
    private val service: LocationService,
    private val store: LocationPermissionStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    @Volatile
    private var owner: Any? = null

    @Volatile
    private var requester: (suspend () -> Boolean)? = null

    /** Binds the activity that can show the system dialog. */
    fun bind(
        owner: Any,
        requester: suspend () -> Boolean,
    ) {
        this.owner = owner
        this.requester = requester
    }

    /** Clears the binding only when [owner] is still the bound activity. */
    fun unbind(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            requester = null
        }
    }

    /**
     * Returns true when location may be used, and false when the user denied
     * the request. An already-denied install is false without a new dialog.
     */
    suspend fun ensureAllowed(): Outcome<Boolean> =
        mutex.withLock {
            if (isAvailable()) {
                return Outcome.Success(true)
            }
            val request = requester ?: return Outcome.Failure(AppError.NotReady)
            if (withContext(ioDispatcher) { store.hasAsked() }) {
                return Outcome.Success(false)
            }
            withContext(ioDispatcher) { store.markAsked() }
                .onFailure { error -> Log.w(TAG, "asked flag not saved: $error") }
            val granted =
                try {
                    request()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    Log.w(TAG, "permission request failed: ${exception.message}")
                    false
                }
            Outcome.Success(granted && isAvailable())
        }

    private fun isAvailable(): Boolean = !service.requiresPermission() || service.hasPermission()

    private companion object {
        const val TAG = "GrassMapsPermission"
    }
}
