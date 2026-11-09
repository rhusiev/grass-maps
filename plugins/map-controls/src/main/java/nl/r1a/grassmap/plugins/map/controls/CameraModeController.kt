package nl.r1a.grassmap.plugins.map.controls

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.error.onFailure
import nl.r1a.grassmap.plugin.api.host.MapHost
import nl.r1a.grassmap.plugin.api.model.CameraUpdate
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.model.TrackingMode

/**
 * Owns the free, follow and heading camera cycle (T-0106).
 *
 * The controller is created by the app composition root with the current map
 * session and the device heading source. It keeps the latest fix so a mode
 * change can recenter at once, and it downgrades [CameraMode.Heading] to
 * [CameraMode.Follow] when neither the fix nor the compass supplies a bearing.
 */
class CameraModeController(
    private val host: () -> MapHost?,
    private val heading: StateFlow<Double?> = MutableStateFlow(null),
    private val store: CameraModeStore = CameraModeStore(),
    private val log: (String) -> Unit = {},
) {
    val mode: StateFlow<CameraMode> = store.mode

    @Volatile
    private var latestFix: LocationFix? = null

    /**
     * True until the camera has been placed on the first fix after a mode
     * change or a host bind. The frame loop owns the centre after that, so a
     * fix only updates the bearing and the puck.
     */
    private var recenterPending: Boolean = true

    /** Advances free -> follow -> heading -> free and returns the new mode. */
    suspend fun cycle(): Outcome<CameraMode> = transition { current -> current.next() }

    /** Toggles between following and free, keeping heading out of the cycle. */
    suspend fun toggle(): Outcome<CameraMode> =
        transition { current ->
            if (current == CameraMode.Free) CameraMode.Follow else CameraMode.Free
        }

    /**
     * Leaves follow and heading for [CameraMode.Free] without moving the camera.
     * A link that centres elsewhere calls this first, so the next fix does not
     * pull the map back to the user.
     */
    suspend fun free(): Outcome<CameraMode> {
        val map = host() ?: return Outcome.Failure(AppError.NotReady)
        store.set(CameraMode.Free)
        map
            .setTrackingMode(CameraMode.Free.trackingMode)
            .onFailure { error -> log("tracking mode rejected: $error") }
        return Outcome.Success(CameraMode.Free)
    }

    /**
     * Points the map north. A heading-up camera cannot stay heading-up at north,
     * so it becomes [CameraMode.Follow] and keeps following.
     */
    suspend fun resetNorth(): Outcome<Double> {
        val map = host() ?: return Outcome.Failure(AppError.NotReady)
        if (store.mode.value == CameraMode.Heading) {
            store.set(CameraMode.Follow)
        }
        map
            .setTrackingMode(store.mode.value.trackingMode)
            .onFailure { error -> log("tracking mode rejected: $error") }
        return when (val outcome = map.moveCamera(CameraUpdate(bearing = NORTH_BEARING))) {
            is Outcome.Success -> Outcome.Success(NORTH_BEARING)
            is Outcome.Failure -> outcome
        }
    }

    /**
     * Applies one fix: draws the puck and, only while a recenter is pending,
     * moves the camera. The map host's frame loop is the single centre owner
     * between fixes, so a per-fix recenter would snap against its interpolation.
     */
    suspend fun onFix(fix: LocationFix?) {
        latestFix = fix
        val map = host() ?: return
        map.setLocation(fix).onFailure { error -> log("puck rejected: $error") }
        applyCamera(map, recenter = recenterPending)
    }

    /** Pushes the latest fix and camera mode to a newly bound host. */
    suspend fun syncToHost() {
        val map = host() ?: return
        val fix = latestFix
        map.setLocation(fix).onFailure { error -> log("puck rejected: $error") }
        recenterPending = true
        applyCamera(map, recenter = true)
    }

    private suspend fun transition(next: (CameraMode) -> CameraMode): Outcome<CameraMode> {
        val map = host() ?: return Outcome.Failure(AppError.NotReady)
        val target = next(store.mode.value)
        store.set(target)
        map
            .setTrackingMode(target.trackingMode)
            .onFailure { error -> log("tracking mode rejected: $error") }
        recenterPending = true
        applyCamera(map, recenter = true)
        return Outcome.Success(target)
    }

    private suspend fun applyCamera(
        map: MapHost,
        recenter: Boolean,
    ) {
        val fix = latestFix ?: return
        when (store.mode.value) {
            CameraMode.Free -> {
                recenterPending = true
            }

            CameraMode.Follow -> {
                if (!recenter) return
                map.moveCamera(CameraUpdate(center = fix.position)).onFailure { error ->
                    log("recenter rejected: $error")
                }
                recenterPending = false
            }

            CameraMode.Heading -> {
                val bearing = headingFor(fix)
                when {
                    bearing == null -> {
                        store.set(CameraMode.Follow)
                        map
                            .setTrackingMode(TrackingMode.Follow)
                            .onFailure { error -> log("tracking mode rejected: $error") }
                        map.moveCamera(CameraUpdate(center = fix.position)).onFailure { error ->
                            log("recenter rejected: $error")
                        }
                        recenterPending = false
                    }

                    recenter -> {
                        map
                            .moveCamera(CameraUpdate(center = fix.position, bearing = bearing))
                            .onFailure { error -> log("heading move rejected: $error") }
                        recenterPending = false
                    }

                    else -> {
                        map
                            .moveCamera(CameraUpdate(bearing = bearing))
                            .onFailure { error -> log("heading move rejected: $error") }
                    }
                }
            }
        }
    }

    /**
     * The direction of travel when the fix moves, else the compass, else null.
     * A null result is what makes heading mode fall back to follow.
     */
    fun headingFor(fix: LocationFix): Double? {
        val moving = (fix.speedMetersPerSecond ?: 0.0) >= MIN_MOVING_SPEED_MPS
        val travel = if (moving) fix.bearingDegrees else null
        return normalize(travel ?: heading.value)
    }

    private fun normalize(bearing: Double?): Double? {
        if (bearing == null || !bearing.isFinite()) return null
        return ((bearing % FULL_CIRCLE_DEGREES) + FULL_CIRCLE_DEGREES) % FULL_CIRCLE_DEGREES
    }

    private companion object {
        const val NORTH_BEARING: Double = 0.0
        const val MIN_MOVING_SPEED_MPS: Double = 0.5
        const val FULL_CIRCLE_DEGREES: Double = 360.0
    }
}
