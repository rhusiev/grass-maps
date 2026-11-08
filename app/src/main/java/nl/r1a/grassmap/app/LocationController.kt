package nl.r1a.grassmap.app

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import nl.r1a.grassmap.core.services.LocationSource
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugins.map.controls.CameraMode
import nl.r1a.grassmap.plugins.map.controls.CameraModeController

/**
 * Process-scoped collector for the active [LocationSource].
 *
 * Every fix, including a null one, goes to the bound map host through
 * [CameraModeController] so the puck stays current and the camera follows or
 * points along the direction of travel according to the current camera mode.
 *
 * The controller holds the map session, not a surface, so it survives Activity
 * recreation and rebinds with [syncToHost].
 *
 * Collection starts without a permission prompt. The source reports null while
 * it has no permission; after the user grants it, [onPermissionGranted]
 * restarts the collector so the cold flow registers provider updates.
 */
class LocationController(
    private val source: LocationSource,
    private val scope: CoroutineScope,
    private val modes: CameraModeController,
) {
    /** The free, follow or heading camera mode. */
    val mode: StateFlow<CameraMode> = modes.mode

    private var job: Job? = null

    /** Starts collection once; later calls do nothing. */
    @Synchronized
    fun start() {
        if (job != null) {
            return
        }
        job =
            scope.launch {
                source.fixes
                    .catch { error ->
                        Log.w(TAG, "location source stopped: ${error.message}")
                    }.collect { fix ->
                        modes.onFix(fix)
                    }
            }
    }

    /**
     * Re-collects the source after the user granted the permission, so the
     * cold flow can register provider updates. A source that needs no
     * permission, such as the replay, keeps running.
     */
    @Synchronized
    fun onPermissionGranted() {
        if (!source.requiresPermission()) {
            return
        }
        job?.cancel()
        job = null
        start()
    }

    /**
     * Toggles between following and free and returns whether the camera now
     * follows. The my-position button uses the full free, follow and heading
     * cycle through [CameraModeController]; this keeps the plain two-state
     * toggle for callers that only need following.
     */
    suspend fun toggleFollowing(): Outcome<Boolean> =
        when (val outcome = modes.toggle()) {
            is Outcome.Success -> Outcome.Success(outcome.value != CameraMode.Free)
            is Outcome.Failure -> outcome
        }

    /** Pushes the latest fix and camera mode to a newly bound host. */
    suspend fun syncToHost() {
        modes.syncToHost()
    }

    private companion object {
        const val TAG = "GrassMapsLocation"
    }
}
