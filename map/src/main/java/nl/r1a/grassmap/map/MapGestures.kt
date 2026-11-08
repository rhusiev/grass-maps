package nl.r1a.grassmap.map

import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.map.MapUiOptions

/**
 * The map's gesture contract (T-0104). The library defaults happen to match it
 * today, but the contract is pinned here so a library upgrade cannot change a
 * gesture silently and the camera tests can name the expected step.
 *
 * | Gesture | Seam |
 * |---|---|
 * | one-finger pan | drag pan |
 * | pinch zoom | two-pointer transform zoom |
 * | two-finger rotate | two-pointer transform rotate |
 * | double-tap zoom in | tap, exactly one zoom level |
 * | one-finger zoom | tap-drag after a double tap |
 * | two-finger tap | one zoom level out, or distance by tap (T-0105) |
 * | long press | select the map point under the finger (T-0112) |
 */
internal object MapGestures {
    /** One double tap changes the zoom by exactly this many levels. */
    const val DOUBLE_TAP_ZOOM_STEP_LEVELS: Double = 1.0

    /** A two-finger tap zooms out by exactly this many levels. */
    const val TWO_FINGER_TAP_ZOOM_LEVELS: Double = 1.0

    /** The longest a two-finger tap may stay down. */
    const val TWO_FINGER_TAP_MAX_MILLIS: Long = 300L

    /** The furthest either finger may travel, in dp, and still count as a tap. */
    const val TWO_FINGER_TAP_MAX_TRAVEL_DP: Float = 12f

    /** The shortest a still one-finger press may stay down to select a point. */
    const val LONG_PRESS_MIN_MILLIS: Long = 500L

    /** The furthest the finger may travel, in dp, and still count as a long press. */
    const val LONG_PRESS_MAX_TRAVEL_DP: Float = 12f

    /** A two-pointer span ratio maps to the same zoom ratio. */
    const val PINCH_ZOOM_SCALE: Double = 1.0

    /** A two-pointer rotation maps to the same bearing change. */
    const val TWO_FINGER_ROTATION_SCALE: Double = 1.0

    /** A full-viewport drag after a double tap changes the zoom by these levels. */
    const val ONE_FINGER_ZOOM_LEVELS_PER_VIEWPORT: Double = 4.0

    /** The camera movements gestures may use; pitch stays off until T-0313. */
    fun interactions(): MapInteractions =
        MapInteractions(MapInteractions.Standard) {
            camera {
                pan { enabled = true }
                zoom { enabled = true }
                rotate { enabled = true }
                pitch { enabled = false }
            }
        }

    /** The input bindings for the gestures above. */
    fun uiOptions(): MapUiOptions =
        MapUiOptions(MapUiOptions.Standard) {
            bindings {
                drag {
                    enabled = true
                    pan { }
                }
                transform {
                    pan { enabled = true }
                    zoom {
                        enabled = true
                        zoomScale = PINCH_ZOOM_SCALE
                    }
                    rotate {
                        enabled = true
                        rotationScale = TWO_FINGER_ROTATION_SCALE
                        allowDuringZoom = true
                    }
                    pitch { enabled = false }
                }
                doubleTap {
                    enabled = true
                    zoomStepLevels = DOUBLE_TAP_ZOOM_STEP_LEVELS
                }
                // The two-finger tap is ours (T-0105), so the engine must not
                // also zoom on it; the detector in MapSurfaceView owns it.
                twoFingerTap {
                    enabled = false
                }
                // The long press is ours too (T-0112), so the selection
                // detector in MapSurfaceView is its only owner.
                longPress {
                    enabled = false
                }
                tapDrag {
                    enabled = true
                    zoomLevelsPerViewport = ONE_FINGER_ZOOM_LEVELS_PER_VIEWPORT
                }
            }
        }
}
