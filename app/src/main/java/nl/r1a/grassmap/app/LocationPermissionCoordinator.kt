package nl.r1a.grassmap.app

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * only after an answer, so a recreation or a timeout while the dialog is open
 * neither consumes the one question nor counts as a denial. Without a bound
 * activity it refuses with [AppError.NotReady] instead of silently allowing
 * location.
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
    private var requester: (suspend () -> Boolean?)? = null

    /** The answer the current dialog will complete, shared by activity recreations. */
    private var pending: CompletableDeferred<Boolean>? = null

    /** Binds the activity that can show the system dialog. */
    fun bind(
        owner: Any,
        requester: suspend () -> Boolean?,
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
     * A null request result means the dialog was never answered (a timeout or
     * a process with no activity), so the asked flag stays unset.
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
            val granted =
                try {
                    request()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    Log.w(TAG, "permission request failed: ${exception.message}")
                    null
                }
            if (granted != null) {
                withContext(ioDispatcher) { store.markAsked() }
                    .onFailure { error -> Log.w(TAG, "asked flag not saved: $error") }
            }
            Outcome.Success(granted == true && isAvailable())
        }

    /**
     * Launches the dialog once and waits for its answer. The pending deferred
     * lives here, not in the Activity, so a recreation keeps the request and
     * the recreated Activity's result callback still delivers the answer.
     */
    suspend fun requestPermission(launch: () -> Unit): Boolean? {
        val deferred =
            pending ?: CompletableDeferred<Boolean>().also { created ->
                pending = created
                launch()
            }
        val answer = withTimeoutOrNull(PERMISSION_TIMEOUT_MILLIS) { deferred.await() }
        if (pending === deferred) {
            pending = null
        }
        return answer
    }

    /** Delivers the system dialog's answer to the waiting request, if any. */
    fun completePermission(granted: Boolean) {
        pending?.complete(granted)
        pending = null
    }

    private fun isAvailable(): Boolean = !service.requiresPermission() || service.hasPermission()

    private companion object {
        const val TAG = "GrassMapsPermission"
        const val PERMISSION_TIMEOUT_MILLIS: Long = 5 * 60_000L
    }
}
