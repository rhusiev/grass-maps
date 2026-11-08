package nl.r1a.grassmap.map

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.withTimeoutOrNull
import nl.r1a.grassmap.plugin.api.model.ScreenOffset
import kotlin.math.hypot
import kotlin.math.max

/**
 * Recognises one long press from pointer events (T-0112). A long press is one
 * pointer held for the contract duration without travelling further than the
 * touch slop. A second finger, a short hold or a moving finger is a different
 * gesture and produces no selection.
 *
 * The recogniser is a plain state machine so a press can be driven from a test
 * without a Compose input scope; [detectLongPress] is only the pointer loop.
 */
internal class LongPressTracker(
    private val maxTravelDp: Float,
    private val minDurationMillis: Long,
) {
    private var startPointerId: Long? = null
    private var startedAtMillis: Long? = null
    private var start: ScreenOffset? = null
    private var travelDp = 0f
    private var pointers = 0
    private var cancelled = false
    private var confirmed = false

    fun onDown(
        pointerId: Long,
        position: ScreenOffset,
        atMillis: Long,
    ) {
        if (startedAtMillis == null) {
            startedAtMillis = atMillis
            startPointerId = pointerId
            start = position
        }
        pointers += 1
        if (pointers > SINGLE_POINTER) {
            cancelled = true
        }
    }

    fun onMove(
        pointerId: Long,
        position: ScreenOffset,
    ) {
        if (pointerId != startPointerId) {
            return
        }
        val origin = start ?: return
        travelDp = max(travelDp, hypot(position.x - origin.x, position.y - origin.y))
    }

    fun onUp(pointerId: Long) {
        if (pointerId == startPointerId) {
            pointers = (pointers - SINGLE_POINTER).coerceAtLeast(0)
        }
    }

    /** Whether the press still held at [atMillis] has become a selection. */
    fun confirm(atMillis: Long): Boolean {
        val began = startedAtMillis ?: return false
        if (confirmed || cancelled || pointers != SINGLE_POINTER) {
            return false
        }
        if (travelDp > maxTravelDp) {
            return false
        }
        if (atMillis - began < minDurationMillis) {
            return false
        }
        confirmed = true
        return true
    }

    /** True once [confirm] accepted this press. */
    fun isConfirmed(): Boolean = confirmed

    /** The point the press began at, which is the point a selection names. */
    fun point(): ScreenOffset? = start

    private companion object {
        const val SINGLE_POINTER = 1
    }
}

/**
 * Watches pointer events on the initial pass, before the engine consumes them,
 * and reports the point of a completed long press. The engine's own long press
 * binding stays off, so one gesture has one owner.
 */
internal suspend fun PointerInputScope.detectLongPress(onPress: (ScreenOffset) -> Unit) {
    val density = this
    awaitEachGesture {
        val tracker =
            LongPressTracker(
                maxTravelDp = MapGestures.LONG_PRESS_MAX_TRAVEL_DP,
                minDurationMillis = MapGestures.LONG_PRESS_MIN_MILLIS,
            )
        val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        tracker.onDown(first.id.value, first.position.toScreenOffset(density), first.uptimeMillis)
        val deadline = first.uptimeMillis + MapGestures.LONG_PRESS_MIN_MILLIS
        var lastUptime = first.uptimeMillis
        var tracking = true
        while (tracking) {
            val event =
                if (tracker.isConfirmed()) {
                    awaitPointerEvent(PointerEventPass.Initial)
                } else {
                    // A held finger produces no events, so the press is
                    // confirmed when this wait runs out.
                    val remaining = (deadline - lastUptime).coerceAtLeast(1L)
                    withTimeoutOrNull(remaining) { awaitPointerEvent(PointerEventPass.Initial) }
                }
            if (event == null) {
                if (tracker.confirm(deadline)) {
                    tracker.point()?.let(onPress)
                } else {
                    tracking = false
                }
                continue
            }
            lastUptime = event.changes.maxOfOrNull { it.uptimeMillis } ?: lastUptime
            trackChanges(event.changes, tracker, density)
            if (event.changes.none { it.pressed }) {
                tracking = false
            }
        }
    }
}

private fun trackChanges(
    changes: List<PointerInputChange>,
    tracker: LongPressTracker,
    density: Density,
) {
    for (change in changes) {
        val position = change.position.toScreenOffset(density)
        when {
            change.pressed && !change.previousPressed -> {
                tracker.onDown(change.id.value, position, change.uptimeMillis)
            }

            change.pressed -> {
                tracker.onMove(change.id.value, position)
            }

            change.previousPressed -> {
                tracker.onUp(change.id.value)
            }
        }
    }
}

private fun Offset.toScreenOffset(density: Density): ScreenOffset = with(density) { ScreenOffset(x = x.toDp().value, y = y.toDp().value) }
