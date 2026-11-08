package nl.r1a.grassmap.plugins.map.controls

import kotlinx.serialization.Serializable
import nl.r1a.grassmap.plugin.api.model.TrackingMode

/**
 * The camera mode the my-position button cycles through (T-0106).
 *
 * [Free] leaves the camera where the user puts it. [Follow] recenters on every
 * fix but keeps the current bearing. [Heading] recenters and points the map
 * along the direction of travel, falling back to [Follow] when no bearing is
 * available.
 */
@Serializable
enum class CameraMode {
    Free,
    Follow,
    Heading,
    ;

    /** The next mode in the free, follow, heading cycle. */
    fun next(): CameraMode = entries[(ordinal + 1) % entries.size]

    /** The tracking mode the host needs for this camera mode. */
    val trackingMode: TrackingMode
        get() =
            when (this) {
                Free -> TrackingMode.Free
                Follow -> TrackingMode.Follow
                Heading -> TrackingMode.FollowWithHeading
            }
}
