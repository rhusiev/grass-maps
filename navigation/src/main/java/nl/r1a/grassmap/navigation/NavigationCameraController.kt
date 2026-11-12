package nl.r1a.grassmap.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.MapHost
import nl.r1a.grassmap.plugin.api.model.CameraUpdate
import nl.r1a.grassmap.plugin.api.model.LocationFix

/**
 * The navigation camera (T-0313): auto zoom, auto-centre and tilt.
 *
 * The controller is pure logic plus the [MapHost] seam and an injected clock. It
 * consumes the published [NavigationState] stream and drives the camera with
 * [MapHost.animateCamera]. Only [NavigationPhase.Following] and
 * [NavigationPhase.Rerouting] move the camera; a paused, idle, arrived or
 * stopped session holds it where it is.
 *
 * Auto zoom follows [CameraSpeedTable], whose speed comes from consecutive
 * fixes rather than the fix's own speed field. The first fix has no interval, so
 * the zoom is not written until the second one.
 *
 * Auto-centre keeps the camera on the fix. A manual pan is reported through
 * [onManualPan]; the camera then stays free until
 * [RECENTRE_DELAY_MILLIS] of clock time has passed, and the next fix recentres
 * it. A second pan restarts the delay. The delay is measured from the pan, not
 * from a fix, so a stopped source still waits the same time.
 *
 * Tilt is on by default in round 5. [setTilt] on pitches the camera to
 * [TILT_DEGREES] and off restores [FLAT_DEGREES].
 *
 * The controller does not set a tracking mode. A screen that binds it must
 * leave the host in `TrackingMode.Free`, or the host's own follow loop and this
 * controller will both write the centre. The navigation screen (T-0320) binds
 * the switches and reports a pan through [onManualPan].
 */
class NavigationCameraController(
    private val host: () -> MapHost?,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    settings: NavigationCameraSettings = NavigationCameraSettings(),
    private val log: (String) -> Unit = {},
) {
    private val mutableSettings = MutableStateFlow(settings)

    /** The three switches, live for a settings screen. */
    val settings: StateFlow<NavigationCameraSettings> = mutableSettings.asStateFlow()

    private val speedTable = CameraSpeedTable()
    private var previousFix: LocationFix? = null
    private var hasMeasuredInterval: Boolean = false

    /** The zoom this controller last wrote, so an unchanged band writes nothing. */
    private var appliedZoom: Double? = null

    /** The tilt this controller last wrote; a flat camera is the initial state. */
    private var appliedTilt: Double = FLAT_DEGREES

    /** When the user last panned; null while the camera follows. */
    private var pannedAtMillis: Long? = null

    /** Applies one published state; a phase without guidance is a no-op. */
    suspend fun onState(state: NavigationState) {
        when (state.phase) {
            NavigationPhase.Following,
            NavigationPhase.Rerouting,
            -> {
                Unit
            }

            NavigationPhase.Idle,
            NavigationPhase.Paused,
            NavigationPhase.Arrived,
            NavigationPhase.Stopped,
            -> {
                // A resumed session starts a new speed interval, so the gap spent
                // paused cannot read as a very slow speed and drop the zoom.
                previousFix = null
                return
            }
        }
        val fix = state.fix ?: return
        val band = observeSpeed(fix)
        val map = host() ?: return
        val update = nextUpdate(fix, band)
        if (update == NO_UPDATE) {
            return
        }
        when (val outcome = map.animateCamera(update)) {
            is Outcome.Success -> {
                appliedZoom = update.zoom ?: appliedZoom
                appliedTilt = update.tilt ?: appliedTilt
            }

            is Outcome.Failure -> {
                log("navigation camera rejected: ${outcome.error}")
            }
        }
    }

    /** Reports a manual pan; a later pan restarts the recentre delay. */
    fun onManualPan() {
        pannedAtMillis = clockMillis()
    }

    /**
     * Clears the pan state after the screen recentred the camera itself, so the
     * next fix keeps the camera on the position instead of waiting out another
     * delay.
     */
    fun clearPan() {
        pannedAtMillis = null
    }

    /**
     * Resets the camera state at the end of a trip: the next session measures a
     * fresh speed interval and applies its own tilt instead of trusting the
     * pitch left by the finished trip. The held switches are unchanged.
     */
    suspend fun resetCamera() {
        previousFix = null
        hasMeasuredInterval = false
        pannedAtMillis = null
        appliedZoom = null
        val map = host() ?: return
        applyTilt(map, FLAT_DEGREES)
    }

    /** Turns auto zoom on or off; off leaves the current zoom where it is. */
    fun setAutoZoom(enabled: Boolean) {
        if (mutableSettings.value.autoZoom == enabled) {
            return
        }
        mutableSettings.update { it.copy(autoZoom = enabled) }
        if (enabled) {
            // Reapply the band for the current speed on the next fix.
            appliedZoom = null
        }
    }

    /** Turns auto-centre on or off; off leaves a panned camera free. */
    fun setAutoCentre(enabled: Boolean) {
        if (mutableSettings.value.autoCentre == enabled) {
            return
        }
        mutableSettings.update { it.copy(autoCentre = enabled) }
        if (enabled) {
            pannedAtMillis = null
        }
    }

    /** Turns the 45-degree tilt on or off, restoring the flat camera when off. */
    suspend fun setTilt(enabled: Boolean) {
        if (mutableSettings.value.tilt == enabled) {
            return
        }
        mutableSettings.update { it.copy(tilt = enabled) }
        val map = host() ?: return
        applyTilt(map, if (enabled) TILT_DEGREES else FLAT_DEGREES)
    }

    /**
     * Measures the interval since the previous fix and returns the zoom band.
     * A fix that is not newer than the previous one starts no interval; the
     * current band is returned so a settings change can still apply it.
     */
    private fun observeSpeed(fix: LocationFix): CameraSpeedBand? {
        val previous = previousFix
        if (previous == null) {
            previousFix = fix
            return measuredBand()
        }
        val elapsedMillis = fix.timeMillis - previous.timeMillis
        if (elapsedMillis <= 0L) {
            return measuredBand()
        }
        previousFix = fix
        hasMeasuredInterval = true
        val metersPerSecond =
            GeoMath.distanceMeters(previous.position, fix.position) * MILLIS_PER_SECOND / elapsedMillis
        return speedTable.update(metersPerSecond)
    }

    private fun measuredBand(): CameraSpeedBand? = if (hasMeasuredInterval) speedTable.band else null

    private fun nextUpdate(
        fix: LocationFix,
        band: CameraSpeedBand?,
    ): CameraUpdate {
        val current = mutableSettings.value
        val centre = if (recentreDue(current)) fix.position else null
        if (centre != null) {
            pannedAtMillis = null
        }
        val zoom = if (current.autoZoom && band != null && band.zoom != appliedZoom) band.zoom else null
        val tiltTarget = if (current.tilt) TILT_DEGREES else FLAT_DEGREES
        val tilt = if (tiltTarget != appliedTilt) tiltTarget else null
        return CameraUpdate(center = centre, zoom = zoom, tilt = tilt)
    }

    private fun recentreDue(settings: NavigationCameraSettings): Boolean {
        val pannedAt = pannedAtMillis ?: return true
        if (!settings.autoCentre) {
            return false
        }
        return clockMillis() - pannedAt >= RECENTRE_DELAY_MILLIS
    }

    private suspend fun applyTilt(
        map: MapHost,
        degrees: Double,
    ) {
        when (val outcome = map.animateCamera(CameraUpdate(tilt = degrees))) {
            is Outcome.Success -> appliedTilt = degrees
            is Outcome.Failure -> log("navigation tilt rejected: ${outcome.error}")
        }
    }

    companion object {
        /** How long a manual pan keeps the camera free: five seconds. */
        const val RECENTRE_DELAY_MILLIS: Long = 5_000L

        /** The pitch when the tilt switch is on. */
        const val TILT_DEGREES: Double = 45.0

        /** The pitch when the tilt switch is off. */
        const val FLAT_DEGREES: Double = 0.0

        private const val MILLIS_PER_SECOND: Double = 1_000.0

        private val NO_UPDATE = CameraUpdate()
    }
}
