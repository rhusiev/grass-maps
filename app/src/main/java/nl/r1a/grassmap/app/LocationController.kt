package nl.r1a.grassmap.app

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import nl.r1a.grassmap.core.services.LocationSource
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.error.onFailure
import nl.r1a.grassmap.plugin.api.host.MapHost
import nl.r1a.grassmap.plugin.api.model.CameraUpdate
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.model.TrackingMode

/**
 * Process-scoped collector for the active [LocationSource].
 *
 * Every fix, including a null one, goes to the bound [MapHost] so the puck stays
 * current. While following, a fix also recenters the camera. While free, the
 * camera never moves. A map gesture does not stop following yet; the
 * camera-mode button that owns that is T-0106.
 *
 * The controller holds the map session, not a surface, so it survives Activity
 * recreation and rebinds with [syncToHost].
 */
class LocationController(
    private val source: LocationSource,
    private val session: MapSessionHolder,
    private val scope: CoroutineScope,
) {
    private val _isFollowing = MutableStateFlow(false)

    /** Whether the camera follows each fix. */
    val isFollowing: StateFlow<Boolean> = _isFollowing.asStateFlow()

    @Volatile
    private var latestFix: LocationFix? = null

    private var started = false

    /** Starts collection once; later calls do nothing. */
    @Synchronized
    fun start() {
        if (started) {
            return
        }
        started = true
        scope.launch {
            source.fixes
                .catch { error ->
                    Log.w(TAG, "location source stopped: ${error.message}")
                }.collect { fix ->
                    apply(fix)
                }
        }
    }

    /**
     * Toggles following, applies the matching tracking mode and returns the new
     * state. A command before the map binds is [AppError.NotReady].
     */
    suspend fun toggleFollowing(): Outcome<Boolean> {
        val host = session.hostOrNull() ?: return Outcome.Failure(AppError.NotReady)
        val following = !_isFollowing.value
        _isFollowing.value = following
        host
            .setTrackingMode(if (following) TrackingMode.Follow else TrackingMode.Free)
            .onFailure { error -> Log.w(TAG, "tracking mode rejected: $error") }
        if (following) {
            latestFix?.let { center(host, it) }
        }
        return Outcome.Success(following)
    }

    /** Pushes the latest fix and follow state to a newly bound host. */
    suspend fun syncToHost() {
        val host = session.hostOrNull() ?: return
        val fix = latestFix
        host.setLocation(fix).onFailure { error -> Log.w(TAG, "puck rejected: $error") }
        if (_isFollowing.value && fix != null) {
            center(host, fix)
        }
    }

    private suspend fun apply(fix: LocationFix?) {
        latestFix = fix
        val host = session.hostOrNull() ?: return
        host.setLocation(fix).onFailure { error -> Log.w(TAG, "puck rejected: $error") }
        if (_isFollowing.value && fix != null) {
            center(host, fix)
        }
    }

    private suspend fun center(
        host: MapHost,
        fix: LocationFix,
    ) {
        host
            .moveCamera(CameraUpdate(center = fix.position))
            .onFailure { error -> Log.w(TAG, "recenter rejected: $error") }
    }

    private companion object {
        const val TAG = "GrassMapsLocation"
    }
}
