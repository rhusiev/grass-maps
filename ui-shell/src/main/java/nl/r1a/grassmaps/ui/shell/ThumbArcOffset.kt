package nl.r1a.grassmaps.ui.shell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** One step of the one-thumb arc, relative to the bar anchor at the bottom end. */
data class ThumbArcOffset(
    /** Negative towards the start of the screen. */
    val x: Dp,
    /** Negative upwards from the bar. */
    val y: Dp,
)

/**
 * The offsets of [count] buttons on the one-thumb arc (T-0606).
 *
 * The rule: the placed commands keep their bar order. Index 0 is the far, top
 * end of the arc; the last index is the bottom, thumb end. The arc is a quarter
 * circle anchored at the bottom end, so each next button is lower and further
 * towards the start. Free slots are not in the list; the caller passes only the
 * placed buttons.
 */
fun thumbArcOffsets(
    count: Int,
    radius: Dp = ARC_RADIUS_DP.dp,
    lift: Dp = ARC_LIFT_DP.dp,
): List<ThumbArcOffset> {
    if (count <= 0) {
        return emptyList()
    }
    if (count == 1) {
        return listOf(ThumbArcOffset(0.dp, -lift))
    }
    val step = QUARTER_TURN_DEGREES / (count - 1)
    return List(count) { index ->
        val angle = Math.toRadians(index * step)
        ThumbArcOffset(
            x = radius * cos(angle).toFloat() - radius,
            y = -(radius + lift) + radius * sin(angle).toFloat(),
        )
    }
}

private const val ARC_RADIUS_DP = 140
private const val ARC_LIFT_DP = 20
private const val QUARTER_TURN_DEGREES = 90.0
