package nl.r1a.grassmaps.core.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The window flag and the animator scale are scoped to the screen (T-0111). */
class ScreenEffectsControllerTest {
    private val effects = FakeWindowEffects()
    private val controller = ScreenEffectsController(effects)

    @Test
    fun keepScreenOnAddsAndClearsTheFlag() {
        controller.apply(keepScreenOn = true, animationsEnabled = true)
        assertTrue(effects.awake)

        controller.apply(keepScreenOn = false, animationsEnabled = true)
        assertFalse(effects.awake)
    }

    @Test
    fun disablingAnimationsSetsTheScaleToZeroAndRestoresItOnExit() {
        effects.scale = 1.5f

        controller.apply(keepScreenOn = false, animationsEnabled = false)
        assertEquals(0f, effects.scale)

        controller.restore()
        assertEquals(1.5f, effects.scale)
        assertFalse(effects.awake)
    }

    @Test
    fun reenablingAnimationsRestoresTheOriginalScaleWithoutLeavingTheScreen() {
        effects.scale = 0.7f
        controller.apply(keepScreenOn = true, animationsEnabled = false)
        controller.apply(keepScreenOn = true, animationsEnabled = true)

        assertEquals(0.7f, effects.scale)
        assertTrue(effects.awake)
        controller.restore()
        assertFalse(effects.awake)
    }

    private class FakeWindowEffects : WindowEffects {
        var awake = false
        var scale = 1f

        override fun setKeepScreenOn(enabled: Boolean) {
            awake = enabled
        }

        override fun durationScale(): Float = scale

        override fun setDurationScale(scale: Float) {
            this.scale = scale
        }
    }
}
