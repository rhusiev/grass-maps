package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.MapTrack
import nl.r1a.grassmap.plugin.api.model.ScreenOffset
import kotlin.math.hypot

/**
 * Which track a tap on the map selected (T-0415).
 *
 * The engine reports a tap as a screen point; this hit test projects each
 * visible track's points through the same [SearchMarkerProjection] the search
 * markers use and measures the distance to the drawn line. A tap within
 * [TAP_TOLERANCE_DP] of a segment selects the nearest such track, so a line
 * thinner than a finger is still tappable.
 *
 * Hidden tracks never reach this function: the store leaves them out of the
 * drawable list, so a hidden track has no geometry to hit. A track with no
 * drawable segment is skipped for the same reason the map skips it.
 */
object TrackTap {
    /** How far a tap may sit from a drawn line and still select it. */
    const val TAP_TOLERANCE_DP: Float = 14f

    fun hit(
        tracks: List<MapTrack>,
        camera: Camera,
        viewportWidthDp: Float,
        viewportHeightDp: Float,
        tap: ScreenOffset,
        anchor: ScreenOffset = ScreenOffset(0f, 0f),
    ): String? {
        if (viewportWidthDp <= 0f || viewportHeightDp <= 0f) {
            return null
        }
        var bestId: String? = null
        var bestDistance = TAP_TOLERANCE_DP
        for (track in tracks) {
            for (segment in track.segments) {
                var previous: SearchMarkerOffset? = null
                for (point in segment) {
                    val projected =
                        SearchMarkerProjection.offset(
                            position = point,
                            camera = camera,
                            viewportWidthDp = viewportWidthDp,
                            viewportHeightDp = viewportHeightDp,
                            anchor = anchor,
                        )
                    val last = previous
                    if (last != null && (last.visible || projected.visible)) {
                        val distance =
                            distanceToSegment(
                                tap = tap,
                                start = ScreenOffset(last.xDp, last.yDp),
                                end = ScreenOffset(projected.xDp, projected.yDp),
                            )
                        if (distance <= bestDistance) {
                            bestDistance = distance
                            bestId = track.id
                        }
                    }
                    previous = projected
                }
            }
        }
        return bestId
    }

    private fun distanceToSegment(
        tap: ScreenOffset,
        start: ScreenOffset,
        end: ScreenOffset,
    ): Float {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 0f) {
            return hypot(tap.x - start.x, tap.y - start.y)
        }
        val raw = ((tap.x - start.x) * dx + (tap.y - start.y) * dy) / lengthSquared
        val t = raw.coerceIn(0f, 1f)
        return hypot(tap.x - (start.x + t * dx), tap.y - (start.y + t * dy))
    }
}
