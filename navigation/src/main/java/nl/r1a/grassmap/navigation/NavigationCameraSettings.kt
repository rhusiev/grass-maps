package nl.r1a.grassmap.navigation

/**
 * The navigation camera switches (T-0313).
 *
 * [autoZoom] follows the speed table in [CameraSpeedTable]; when it is off the
 * controller never writes a zoom. [autoCentre] returns the camera to the
 * position after a manual pan once [NavigationCameraController.RECENTRE_DELAY_MILLIS]
 * has passed; when it is off a pan leaves the camera free. [tilt] pitches the
 * camera to [NavigationCameraController.TILT_DEGREES]; when it is off the
 * camera is flat. Tilt is on by default in round 5, matching the navigation
 * screen's 45-degree map.
 */
data class NavigationCameraSettings(
    val autoZoom: Boolean = true,
    val autoCentre: Boolean = true,
    val tilt: Boolean = true,
)
