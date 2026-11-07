package nl.r1a.grassmap.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapGesturesTest {
    @Test
    fun doubleTapChangesExactlyOneZoomLevel() {
        assertTrue(
            MapGestures.uiOptions().toString(),
            MapGestures.uiOptions().toString().contains("zoomStepLevels=1.0"),
        )
        assertEquals(1.0, MapGestures.DOUBLE_TAP_ZOOM_STEP_LEVELS, 0.0)
    }

    @Test
    fun oneFingerZoomIsTheTapDragGesture() {
        val options = MapGestures.uiOptions().toString()
        assertTrue(options, options.contains("zoomLevelsPerViewport=4.0"))
    }

    @Test
    fun pinchAndRotateMapOneToOneToTheTracedGesture() {
        val options = MapGestures.uiOptions().toString()
        assertTrue(options, options.contains("zoomScale=1.0"))
        assertTrue(options, options.contains("rotationScale=1.0"))
        assertTrue(options, options.contains("allowDuringZoom=true"))
    }

    @Test
    fun pitchIsNotAGestureUntilThePerspectiveTask() {
        val options = MapGestures.uiOptions().toString()
        assertTrue(options, options.contains("PitchBinding(enabled=false"))
    }

    @Test
    fun aTwoFingerTapIsPinnedToTheDistanceToolContract() {
        assertEquals(1.0, MapGestures.TWO_FINGER_TAP_ZOOM_LEVELS, 0.0)
        assertEquals(300L, MapGestures.TWO_FINGER_TAP_MAX_MILLIS)
        assertEquals(12f, MapGestures.TWO_FINGER_TAP_MAX_TRAVEL_DP, 0f)
    }

    @Test
    fun theCameraAllowsOnlyTheGesturesWeShip() {
        val interactions = MapGestures.interactions().toString()
        assertTrue(interactions, interactions.contains("PanCameraConfiguration(enabled=true"))
        assertTrue(interactions, interactions.contains("VelocityCameraConfiguration(enabled=true"))
        assertTrue(interactions, interactions.contains("RotateCameraConfiguration(enabled=true"))
        assertTrue(interactions, interactions.contains("PitchCameraConfiguration(enabled=false"))
    }
}
