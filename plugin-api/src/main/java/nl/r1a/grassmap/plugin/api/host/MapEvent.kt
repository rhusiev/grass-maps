package nl.r1a.grassmap.plugin.api.host

import nl.r1a.grassmap.plugin.api.model.Camera

/**
 * One fact reported by the map engine. Values may grow; match with an else
 * branch.
 */
sealed interface MapEvent {
    /** The engine loaded a base style. */
    data object StyleLoaded : MapEvent

    /** The engine rejected a base style. */
    data class StyleFailed(
        val reason: String,
    ) : MapEvent

    /** The engine drew a frame. [full] means every tile and image was ready. */
    data class FrameRendered(
        val full: Boolean,
    ) : MapEvent

    /** The engine finished all pending work and has nothing more to draw. */
    data object Idle : MapEvent

    /** The camera changed. */
    data class CameraChanged(
        val camera: Camera,
    ) : MapEvent
}
