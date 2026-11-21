package nl.r1a.grassmaps.core.services

/**
 * The two window-side effects of T-0111 behind a small seam. The app supplies
 * the real window and animator calls; tests supply a recorder, so the scoped
 * lifecycle needs no emulator.
 */
interface WindowEffects {
    fun setKeepScreenOn(enabled: Boolean)

    fun durationScale(): Float

    fun setDurationScale(scale: Float)
}

/**
 * Keeps the screen awake and drops the animator scale to zero while the app
 * screen that owns this controller is active. The original scale is captured
 * on the first apply and put back on [restore], so a settings change cannot
 * leave the process with a permanently scaled animator.
 */
class ScreenEffectsController(
    private val effects: WindowEffects,
) {
    private var baseScale: Float? = null

    /** Applies the current values; safe to call on every settings change. */
    fun apply(
        keepScreenOn: Boolean,
        animationsEnabled: Boolean,
    ) {
        effects.setKeepScreenOn(keepScreenOn)
        val base = baseScale ?: effects.durationScale().also { baseScale = it }
        effects.setDurationScale(if (animationsEnabled) base else 0f)
    }

    /** Clears the flag and restores the original scale on screen exit. */
    fun restore() {
        effects.setKeepScreenOn(false)
        baseScale?.let(effects::setDurationScale)
        baseScale = null
    }
}
