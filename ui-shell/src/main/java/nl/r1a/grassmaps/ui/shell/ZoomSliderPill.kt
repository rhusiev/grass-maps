package nl.r1a.grassmaps.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import nl.r1a.grassmaps.plugin.ui.api.LocalShellColors
import nl.r1a.grassmaps.plugin.ui.api.ShellIcon
import nl.r1a.grassmaps.plugin.ui.api.ShellIcons
import kotlin.math.roundToInt

/** The drawn sizes of the round 9 map stack. A 42 dp FAB sits in a 48 dp target. */
object MapControlMetrics {
    const val SLIDER_WIDTH_DP = 44
    const val SLIDER_HEIGHT_DP = 140
    const val FAB_DP = 42
    const val TAP_TARGET_DP = 48
}

/**
 * The discrete zoom positions the slider pill snaps to. Pure Kotlin so a JVM
 * test can pin the bounds and step the camera commands already use.
 */
data class ZoomSliderSpec(
    val min: Float,
    val max: Float,
    val step: Float,
) {
    init {
        require(min < max) { "the zoom range must have room to move" }
        require(step > 0f) { "the zoom step must move the camera" }
    }

    val count: Int = ((max - min) / step).roundToInt() + 1

    fun valueAt(index: Int): Float = (min + index.coerceIn(0, count - 1) * step).coerceAtMost(max)

    fun indexOf(value: Float): Int = ((value - min) / step).roundToInt().coerceIn(0, count - 1)

    /** The top of the track is the maximum zoom; dragging down zooms out. */
    fun valueAtFraction(fraction: Float): Float = valueAt(((1f - fraction.coerceIn(0f, 1f)) * (count - 1)).roundToInt())

    fun fractionOf(value: Float): Float = if (count <= 1) 0f else 1f - indexOf(value) / (count - 1).toFloat()
}

/** The resolved text and model the pill needs from its host. */
data class ZoomSliderContent(
    val spec: ZoomSliderSpec,
    val value: Float,
    val slideDescription: String,
    val zoomInDescription: String,
    val zoomOutDescription: String,
)

/**
 * The vertical zoom slider pill (T-0612, round 9). The + and - ends keep the
 * camera commands' spoken labels and step the zoom; dragging the dashed track
 * reports an absolute target. The host maps that target to existing camera
 * commands, so this control owns no camera state of its own.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun ZoomSliderPill(
    content: ZoomSliderContent,
    onZoomChange: (Float) -> Unit,
    onZoomStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalShellColors.current
    val spec = content.spec
    val corner = RoundedCornerShape((MapControlMetrics.SLIDER_WIDTH_DP / 2).dp)
    Column(
        modifier =
            modifier
                .size(MapControlMetrics.SLIDER_WIDTH_DP.dp, MapControlMetrics.SLIDER_HEIGHT_DP.dp)
                .background(colors.glass, corner)
                .border(1.dp, colors.ring, corner),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SliderEnd(ShellIcons.Plus, content.zoomInDescription) { onZoomStep(1) }
        BoxWithConstraints(
            modifier =
                Modifier
                    .width(TRACK_WIDTH_DP.dp)
                    .weight(1f)
                    .background(colors.soft, RoundedCornerShape(TRACK_WIDTH_DP.dp / 2))
                    .pointerInput(spec) {
                        detectTapGestures { offset -> onZoomChange(spec.valueAtFraction(offset.y / size.height)) }
                    }.pointerInput(spec) {
                        detectDragGestures(
                            onDragStart = { offset -> onZoomChange(spec.valueAtFraction(offset.y / size.height)) },
                            onDrag = { change, _ -> onZoomChange(spec.valueAtFraction(change.position.y / size.height)) },
                        )
                    }.semantics { contentDescription = content.slideDescription },
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.padding(top = DASH_TOP_DP.dp),
                verticalArrangement = Arrangement.spacedBy(DASH_GAP_DP.dp),
            ) {
                repeat(DASH_COUNT) {
                    Box(
                        Modifier
                            .size(DASH_WIDTH_DP.dp, DASH_THICKNESS_DP.dp)
                            .background(colors.faint, RoundedCornerShape(DASH_THICKNESS_DP.dp / 2)),
                    )
                }
            }
            Box(
                modifier =
                    Modifier
                        .offset(y = (maxHeight - THUMB_DP.dp) * (1f - spec.fractionOf(content.value)))
                        .size(THUMB_DP.dp)
                        .background(colors.ink, CircleShape),
            )
        }
        SliderEnd(ShellIcons.Minus, content.zoomOutDescription) { onZoomStep(-1) }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SliderEnd(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    val colors = LocalShellColors.current
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(MapControlMetrics.TAP_TARGET_DP.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        ShellIcon(icon, colors.ink, Modifier.size(END_ICON_DP.dp))
    }
}

private const val TRACK_WIDTH_DP = 12
private const val DASH_COUNT = 6
private const val DASH_WIDTH_DP = 16
private const val DASH_THICKNESS_DP = 2
private const val DASH_GAP_DP = 4
private const val DASH_TOP_DP = 3
private const val THUMB_DP = 8
private const val END_ICON_DP = 20
