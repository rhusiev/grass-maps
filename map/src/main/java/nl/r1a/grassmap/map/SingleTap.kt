package nl.r1a.grassmap.map

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import nl.r1a.grassmap.plugin.api.model.ScreenOffset
import kotlin.math.hypot
import kotlin.math.max

/**
 * Watches pointer events on the initial pass and reports the point of a
 * finished one-finger tap (T-0415). Nothing is consumed, so the engine keeps
 * its pan, pinch, double-tap and one-finger zoom.
 *
 * A tap is one pointer, down for less than [MapGestures.TAP_MAX_MILLIS] and
 * moved no more than [MapGestures.TAP_MAX_TRAVEL_DP]. A second finger, a longer
 * hold (that is the long press, which has its own owner) or a moving finger
 * reports nothing. The map screen only uses the point to hit-test a track, so
 * a tap that is the first half of a double tap may also select a track; the
 * engine still zooms, and selection is idempotent.
 */
internal suspend fun PointerInputScope.detectSingleTap(onTap: (ScreenOffset) -> Unit) {
    val density = this
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val start = first.position.toScreenOffset(density)
        var travelDp = 0f
        var cancelled = false
        var tracking = true
        while (tracking) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.count { change -> change.pressed }
            if (pressed > SINGLE_POINTER) {
                cancelled = true
            }
            val change = event.changes.firstOrNull { candidate -> candidate.id == first.id } ?: continue
            val position = change.position.toScreenOffset(density)
            travelDp = max(travelDp, hypot(position.x - start.x, position.y - start.y))
            if (!change.pressed && change.previousPressed) {
                tracking = false
                val elapsed = change.uptimeMillis - first.uptimeMillis
                if (!cancelled &&
                    pressed == 0 &&
                    travelDp <= MapGestures.TAP_MAX_TRAVEL_DP &&
                    elapsed <= MapGestures.TAP_MAX_MILLIS
                ) {
                    onTap(start)
                }
            }
        }
    }
}

private fun Offset.toScreenOffset(density: Density): ScreenOffset = with(density) { ScreenOffset(x = x.toDp().value, y = y.toDp().value) }

private const val SINGLE_POINTER = 1
