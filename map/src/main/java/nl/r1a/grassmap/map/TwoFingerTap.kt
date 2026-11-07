package nl.r1a.grassmap.map

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.unit.Density
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.ScreenOffset
import kotlin.math.hypot
import kotlin.math.max

/** Whether a two-finger tap measures a distance instead of zooming out. */
fun interface DistanceByTapSetting {
    fun isEnabled(): Boolean
}

/** The map point under a screen point, or null while the engine cannot answer. */
internal fun interface MapScreenProjector {
    fun geoPointAt(point: ScreenOffset): GeoPoint?
}

/** What a completed two-finger tap asks the map to do. */
internal sealed interface TwoFingerTapAction {
    data class ZoomOut(
        val zoom: Double,
    ) : TwoFingerTapAction

    data class Distance(
        val meters: Double,
    ) : TwoFingerTapAction

    data object Ignored : TwoFingerTapAction
}

/**
 * The result of one two-finger tap. With distance-by-tap off it is one zoom
 * level out; with it on the two touch points are projected to the map and the
 * ground distance between them is measured (T-0105).
 */
internal fun twoFingerTapAction(
    distanceByTap: Boolean,
    cameraZoom: Double,
    from: ScreenOffset,
    to: ScreenOffset,
    projector: MapScreenProjector,
): TwoFingerTapAction {
    if (!distanceByTap) {
        return TwoFingerTapAction.ZoomOut(cameraZoom - MapGestures.TWO_FINGER_TAP_ZOOM_LEVELS)
    }
    val first = projector.geoPointAt(from) ?: return TwoFingerTapAction.Ignored
    val second = projector.geoPointAt(to) ?: return TwoFingerTapAction.Ignored
    return TwoFingerTapAction.Distance(MapDistance.meters(first, second))
}

/**
 * Recognises one two-finger tap from pointer events. A tap is exactly two
 * pointers, held no longer than the contract allows, and neither of them
 * travels further than the touch slop. Three fingers, a moving finger or a long
 * hold is a different gesture and produces no tap.
 */
internal class TwoFingerTapTracker(
    private val maxTravelDp: Float,
    private val maxDurationMillis: Long,
) {
    private val starts = LinkedHashMap<Long, ScreenOffset>()
    private val active = mutableSetOf<Long>()
    private var startMillis: Long? = null
    private var peakPointers = 0
    private var travelDp = 0f

    fun reset() {
        starts.clear()
        active.clear()
        startMillis = null
        peakPointers = 0
        travelDp = 0f
    }

    fun onDown(
        pointerId: Long,
        position: ScreenOffset,
        atMillis: Long,
    ) {
        if (active.isEmpty()) {
            startMillis = atMillis
        }
        starts[pointerId] = position
        active += pointerId
        peakPointers = max(peakPointers, active.size)
    }

    fun onMove(
        pointerId: Long,
        position: ScreenOffset,
    ) {
        val start = starts[pointerId] ?: return
        travelDp = max(travelDp, hypot(position.x - start.x, position.y - start.y))
    }

    fun onUp(pointerId: Long) {
        active -= pointerId
    }

    /** Whether the gesture that ended at [atMillis] is a two-finger tap. */
    fun isTap(atMillis: Long): Boolean {
        val started = startMillis ?: return false
        return peakPointers == TWO_FINGER_COUNT &&
            atMillis - started <= maxDurationMillis &&
            travelDp <= maxTravelDp
    }

    /** The two touch points of the last gesture, in the order the fingers landed. */
    fun tapPoints(): Pair<ScreenOffset, ScreenOffset>? {
        if (starts.size != TWO_FINGER_COUNT) {
            return null
        }
        val points = starts.values.toList()
        return points[0] to points[1]
    }

    private companion object {
        const val TWO_FINGER_COUNT = 2
    }
}

/**
 * Watches pointer events on the initial pass, before the engine consumes them,
 * and reports a completed two-finger tap.
 */
internal suspend fun PointerInputScope.detectTwoFingerTap(onTap: (from: ScreenOffset, to: ScreenOffset) -> Unit) {
    val tracker =
        TwoFingerTapTracker(
            maxTravelDp = MapGestures.TWO_FINGER_TAP_MAX_TRAVEL_DP,
            maxDurationMillis = MapGestures.TWO_FINGER_TAP_MAX_MILLIS,
        )
    val density = this
    awaitEachGesture {
        tracker.reset()
        val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        tracker.onDown(first.id.value, first.position.toScreenOffset(density), first.uptimeMillis)
        var endedAt: Long? = null
        while (endedAt == null) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            trackChanges(event.changes, tracker, density)
            if (event.changes.none { it.pressed }) {
                endedAt = event.changes.maxOfOrNull { it.uptimeMillis } ?: first.uptimeMillis
            }
        }
        if (tracker.isTap(endedAt)) {
            tracker.tapPoints()?.let { (from, to) -> onTap(from, to) }
        }
    }
}

private fun trackChanges(
    changes: List<PointerInputChange>,
    tracker: TwoFingerTapTracker,
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
