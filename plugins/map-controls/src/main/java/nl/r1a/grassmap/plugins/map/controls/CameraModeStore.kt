package nl.r1a.grassmap.plugins.map.controls

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The process-scoped camera mode. The plugin stream and the app-side controller
 * read and write the same store, so the control bar and the control channel
 * always agree on the current mode.
 */
class CameraModeStore(
    initial: CameraMode = CameraMode.Free,
) {
    private val state = MutableStateFlow(initial)

    val mode: StateFlow<CameraMode> = state.asStateFlow()

    /** Moves to the next mode in the cycle and returns it. */
    @Synchronized
    fun next(): CameraMode {
        val next = state.value.next()
        state.value = next
        return next
    }

    @Synchronized
    fun set(mode: CameraMode) {
        state.value = mode
    }
}

/**
 * The process-level map-controls state. It is deliberately small: the camera
 * mode is the only state shared between the plugin stream and the app controller.
 */
object MapControls {
    val modes: CameraModeStore = CameraModeStore()
}
