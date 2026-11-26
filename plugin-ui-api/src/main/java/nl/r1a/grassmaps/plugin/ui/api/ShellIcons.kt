@file:Suppress("ktlint:standard:function-naming")

package nl.r1a.grassmaps.plugin.ui.api

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The shell's drawn icons, one vector each, built from the round-5 to round-9
 * prototype paths. A glyph is decoration beside a label or inside a described
 * control, so every icon is tinted by its caller from [ShellColors] and clears
 * semantics. Keeping the vectors here gives the shell and its UI plugins one
 * source instead of a separate copy in each module.
 */
object ShellIcons {
    val Close =
        icon("close") {
            stroked {
                moveTo(6.5f, 6.5f)
                lineTo(17.5f, 17.5f)
                moveTo(17.5f, 6.5f)
                lineTo(6.5f, 17.5f)
            }
        }
    val ChevronUp =
        icon("chev_up") {
            stroked {
                moveTo(6f, 14.5f)
                lineTo(12f, 8.5f)
                lineTo(18f, 14.5f)
            }
        }
    val ChevronDown =
        icon("chev_down") {
            stroked {
                moveTo(6f, 9.5f)
                lineTo(12f, 15.5f)
                lineTo(18f, 9.5f)
            }
        }
    val ChevronLeft =
        icon("chev_left") {
            stroked {
                moveTo(14.5f, 6f)
                lineTo(8.5f, 12f)
                lineTo(14.5f, 18f)
            }
        }
    val ChevronRight =
        icon("chev_right") {
            stroked {
                moveTo(9.5f, 6f)
                lineTo(15.5f, 12f)
                lineTo(9.5f, 18f)
            }
        }
    val Check =
        icon("check") {
            stroked {
                moveTo(5f, 12.5f)
                lineTo(10f, 17f)
                lineTo(19f, 7.5f)
            }
        }
    val Play =
        icon("play") {
            filled {
                moveTo(8f, 5f)
                lineTo(8f, 19f)
                lineTo(19f, 12f)
                close()
            }
        }

    val Pause =
        icon("pause") {
            stroked {
                moveTo(9f, 5.5f)
                lineTo(9f, 18.5f)
                moveTo(15f, 5.5f)
                lineTo(15f, 18.5f)
            }
        }

    val Search =
        icon("search") {
            stroked { circle(11f, 11f, 6.5f) }
            stroked {
                moveTo(16f, 16f)
                lineTo(20.5f, 20.5f)
            }
        }

    val Locate =
        icon("locate") {
            stroked { circle(12f, 12f, 7f) }
            filled { circle(12f, 12f, 2.6f) }
            stroked {
                moveTo(12f, 2f)
                lineTo(12f, 5f)
                moveTo(12f, 19f)
                lineTo(12f, 22f)
                moveTo(2f, 12f)
                lineTo(5f, 12f)
                moveTo(19f, 12f)
                lineTo(22f, 12f)
            }
        }

    val Flag =
        icon("flag") {
            stroked {
                moveTo(5.5f, 21f)
                lineTo(5.5f, 4f)
                moveTo(5.5f, 4.5f)
                lineTo(16.5f, 4.5f)
                lineTo(14.3f, 8.2f)
                lineTo(16.5f, 12f)
                lineTo(5.5f, 12f)
            }
        }

    val Via =
        icon("via") {
            stroked { circle(12f, 12f, 3f) }
            stroked {
                moveTo(12f, 3f)
                lineTo(12f, 9f)
                moveTo(12f, 15f)
                lineTo(12f, 21f)
            }
        }

    val Speed =
        icon("speed") {
            stroked {
                moveTo(4.5f, 17.5f)
                arcTo(8f, 8f, 0f, true, true, 19.5f, 17.5f)
                moveTo(12f, 14f)
                lineTo(16f, 9.5f)
            }
        }

    val Tune =
        icon("tune") {
            stroked {
                moveTo(4f, 7f)
                lineTo(13f, 7f)
                moveTo(17f, 7f)
                lineTo(20f, 7f)
                moveTo(4f, 17f)
                lineTo(7f, 17f)
                moveTo(11f, 17f)
                lineTo(20f, 17f)
            }
            stroked {
                circle(15f, 7f, 2f)
                circle(9f, 17f, 2f)
            }
        }

    val Bell =
        icon("bell") {
            stroked {
                moveTo(6f, 16f)
                lineTo(6f, 11f)
                arcTo(6f, 6f, 0f, false, true, 18f, 11f)
                lineTo(18f, 16f)
                lineTo(19.5f, 18f)
                lineTo(4.5f, 18f)
                close()
                moveTo(10f, 20.5f)
                arcTo(2f, 2f, 0f, false, true, 14f, 20.5f)
            }
        }

    val Crossing =
        icon("crossing") {
            stroked {
                moveTo(5f, 5f)
                lineTo(19f, 19f)
                moveTo(19f, 5f)
                lineTo(5f, 19f)
                moveTo(12f, 15f)
                lineTo(12f, 21f)
            }
        }

    val Tunnel =
        icon("tunnel") {
            stroked {
                moveTo(3f, 20f)
                lineTo(3f, 12f)
                arcTo(9f, 9f, 0f, false, true, 21f, 12f)
                lineTo(21f, 20f)
                moveTo(7f, 20f)
                lineTo(7f, 13f)
                arcTo(5f, 5f, 0f, false, true, 17f, 13f)
                lineTo(17f, 20f)
                moveTo(2f, 20f)
                lineTo(22f, 20f)
            }
        }

    val Warning =
        icon("warning") {
            stroked {
                moveTo(12f, 3.5f)
                lineTo(21.5f, 20f)
                lineTo(2.5f, 20f)
                close()
            }
            stroked {
                moveTo(12f, 10f)
                lineTo(12f, 14.5f)
            }
            filled { circle(12f, 17.2f, 0.7f) }
        }

    val Drop =
        icon("drop") {
            stroked {
                moveTo(12f, 3.5f)
                curveTo(12f, 3.5f, 6f, 10.1f, 6f, 14f)
                arcTo(6f, 6f, 0f, false, false, 18f, 14f)
                curveTo(18f, 10.1f, 12f, 3.5f, 12f, 3.5f)
                close()
            }
        }

    val Reroute =
        icon("reroute") {
            stroked {
                moveTo(5f, 19f)
                lineTo(5f, 9f)
                arcTo(4f, 4f, 0f, false, true, 9f, 5f)
                lineTo(18f, 5f)
                moveTo(15f, 2f)
                lineTo(18f, 5f)
                lineTo(15f, 8f)
                moveTo(19f, 15f)
                lineTo(19f, 19f)
                lineTo(15f, 19f)
            }
        }

    val SignalOff =
        icon("signal_off") {
            stroked { circle(12f, 12f, 3f) }
            stroked {
                moveTo(12f, 2f)
                lineTo(12f, 5f)
                moveTo(12f, 19f)
                lineTo(12f, 22f)
                moveTo(2f, 12f)
                lineTo(5f, 12f)
                moveTo(19f, 12f)
                lineTo(22f, 12f)
                moveTo(4f, 4f)
                lineTo(20f, 20f)
            }
        }

    val Pin =
        icon("pin") {
            stroked {
                moveTo(12f, 21f)
                curveTo(12f, 21f, 5.5f, 14.8f, 5.5f, 10f)
                arcTo(6.5f, 6.5f, 0f, false, true, 18.5f, 10f)
                curveTo(18.5f, 14.8f, 12f, 21f, 12f, 21f)
                close()
            }
            stroked { circle(12f, 10f, 2.3f) }
        }

    val Tilt3d =
        icon("tilt3d") {
            stroked {
                moveTo(4f, 18f)
                lineTo(9f, 6f)
                lineTo(15f, 6f)
                lineTo(20f, 18f)
                close()
            }
            stroked {
                moveTo(6.5f, 12f)
                lineTo(17.5f, 12f)
            }
        }

    val Plus =
        icon("plus") {
            stroked {
                moveTo(12f, 5f)
                lineTo(12f, 19f)
                moveTo(5f, 12f)
                lineTo(19f, 12f)
            }
        }
    val Minus =
        icon("minus") {
            stroked {
                moveTo(5f, 12f)
                lineTo(19f, 12f)
            }
        }

    val Compass =
        icon("compass") {
            filled {
                moveTo(12f, 2.8f)
                lineTo(16f, 12f)
                lineTo(8f, 12f)
                close()
            }
            path(fill = SolidColor(Color.White.copy(alpha = DIM_ALPHA))) {
                moveTo(12f, 21.2f)
                lineTo(8f, 12f)
                lineTo(16f, 12f)
                close()
            }
        }

    val Star =
        icon("star") {
            stroked {
                moveTo(12f, 3.5f)
                lineTo(14.6f, 8.9f)
                lineTo(20.5f, 9.7f)
                lineTo(16.2f, 13.8f)
                lineTo(17.2f, 19.6f)
                lineTo(12f, 16.9f)
                lineTo(6.8f, 19.6f)
                lineTo(7.8f, 13.8f)
                lineTo(3.5f, 9.7f)
                lineTo(9.4f, 8.9f)
                close()
            }
        }

    val Route =
        icon("route") {
            stroked {
                circle(6f, 18f, 2.2f)
                circle(18f, 6f, 2.2f)
            }
            stroked {
                moveTo(8.2f, 18f)
                lineTo(15f, 18f)
                arcTo(3.5f, 3.5f, 0f, false, true, 15f, 11f)
                lineTo(9f, 11f)
                arcTo(3.5f, 3.5f, 0f, false, false, 9f, 4f)
                lineTo(15.8f, 4f)
            }
        }

    val Track =
        icon("track") {
            stroked {
                moveTo(4f, 18f)
                curveTo(7f, 18f, 7f, 13f, 10f, 13f)
                curveTo(13f, 13f, 13f, 17f, 16f, 17f)
                curveTo(18f, 17f, 18f, 8f, 20f, 6f)
            }
            stroked { circle(4f, 18f, 1.6f) }
        }

    // The list screens' icons, traced from round 8 `x-saved` and `x-tracks`
    // (round4/_build/r4base.py:20 and round8/_build/s_cleanup8.py:20).
    val Eye =
        icon("eye") {
            stroked {
                moveTo(2.5f, 12f)
                curveTo(2.5f, 12f, 6f, 5.5f, 12f, 5.5f)
                curveTo(18f, 5.5f, 21.5f, 12f, 21.5f, 12f)
                curveTo(21.5f, 12f, 18f, 18.5f, 12f, 18.5f)
                curveTo(6f, 18.5f, 2.5f, 12f, 2.5f, 12f)
                close()
            }
            stroked { circle(12f, 12f, 2.8f) }
        }

    val EyeOff =
        icon("eye_off") {
            stroked {
                moveTo(4f, 4f)
                lineTo(20f, 20f)
                moveTo(9.8f, 6f)
                arcTo(10f, 10f, 0f, false, true, 12f, 5.5f)
                curveTo(18f, 5.5f, 21.5f, 12f, 21.5f, 12f)
                arcTo(17f, 17f, 0f, false, true, 18.9f, 15.4f)
                moveTo(6.4f, 7.6f)
                arcTo(17f, 17f, 0f, false, false, 2.5f, 12f)
                curveTo(2.5f, 12f, 6f, 18.5f, 12f, 18.5f)
                arcTo(9.6f, 9.6f, 0f, false, false, 16.4f, 17.4f)
            }
        }

    val MapFolded =
        icon("map") {
            stroked {
                moveTo(9f, 4.5f)
                lineTo(3.5f, 6.5f)
                lineTo(3.5f, 19.5f)
                lineTo(9f, 17.5f)
                lineTo(15f, 19.5f)
                lineTo(20.5f, 17.5f)
                lineTo(20.5f, 4.5f)
                lineTo(15f, 6.5f)
                close()
                moveTo(9f, 4.5f)
                lineTo(9f, 17.5f)
                moveTo(15f, 6.5f)
                lineTo(15f, 19.5f)
            }
        }

    val Sort =
        icon("sort") {
            stroked {
                moveTo(7f, 5f)
                lineTo(7f, 19f)
                lineTo(4f, 16f)
                moveTo(7f, 19f)
                lineTo(10f, 16f)
                moveTo(13f, 6f)
                lineTo(20f, 6f)
                moveTo(13f, 11f)
                lineTo(18f, 11f)
                moveTo(13f, 16f)
                lineTo(16f, 16f)
            }
        }

    val Folder =
        icon("folder") {
            stroked {
                moveTo(3.5f, 6.5f)
                arcTo(2f, 2f, 0f, false, true, 5.5f, 4.5f)
                lineTo(9.5f, 4.5f)
                lineTo(11.5f, 7f)
                lineTo(18.5f, 7f)
                arcTo(2f, 2f, 0f, false, true, 20.5f, 9f)
                lineTo(20.5f, 18f)
                arcTo(2f, 2f, 0f, false, true, 18.5f, 20f)
                lineTo(5.5f, 20f)
                arcTo(2f, 2f, 0f, false, true, 3.5f, 18f)
                close()
            }
        }

    // The guidance card's manoeuvre arrows. The four first kinds are traced
    // from `turn_arrow` in round5/_build/ui.py:108; the rest use the same
    // 64 px grid, stroked shaft and filled triangular head for the other
    // values NavigationTurnArrow can emit.
    val TurnStraight =
        turnArrow("turn_straight") {
            stroked(TURN_STROKE) {
                moveTo(32f, 58f)
                lineTo(32f, 18f)
            }
            filled {
                moveTo(32f, 6f)
                lineTo(20f, 20f)
                lineTo(44f, 20f)
                close()
            }
        }

    val TurnSlightRight =
        turnArrow("turn_slight_right") {
            stroked(TURN_STROKE) {
                moveTo(28f, 58f)
                lineTo(28f, 36f)
                lineTo(42f, 20f)
            }
            filled {
                moveTo(50f, 10f)
                lineTo(36f, 14f)
                lineTo(46f, 23f)
                close()
            }
        }

    val TurnSlightLeft =
        turnArrow("turn_slight_left") {
            stroked(TURN_STROKE) {
                moveTo(36f, 58f)
                lineTo(36f, 36f)
                lineTo(22f, 20f)
            }
            filled {
                moveTo(14f, 10f)
                lineTo(28f, 14f)
                lineTo(18f, 23f)
                close()
            }
        }

    val TurnRight =
        turnArrow("turn_right") {
            stroked(TURN_STROKE) {
                moveTo(26f, 58f)
                lineTo(26f, 31f)
                arcTo(9f, 9f, 0f, false, true, 35f, 22f)
                lineTo(47f, 22f)
            }
            filled {
                moveTo(56f, 22f)
                lineTo(45f, 12f)
                lineTo(45f, 32f)
                close()
            }
        }

    val TurnLeft =
        turnArrow("turn_left") {
            stroked(TURN_STROKE) {
                moveTo(38f, 58f)
                lineTo(38f, 31f)
                arcTo(9f, 9f, 0f, false, false, 29f, 22f)
                lineTo(17f, 22f)
            }
            filled {
                moveTo(8f, 22f)
                lineTo(19f, 12f)
                lineTo(19f, 32f)
                close()
            }
        }

    val TurnSharpRight =
        turnArrow("turn_sharp_right") {
            stroked(TURN_STROKE) {
                moveTo(26f, 58f)
                lineTo(26f, 38f)
                arcTo(9f, 9f, 0f, false, true, 35f, 29f)
                lineTo(48f, 29f)
            }
            filled {
                moveTo(60f, 29f)
                lineTo(48f, 18f)
                lineTo(48f, 40f)
                close()
            }
        }

    val TurnSharpLeft =
        turnArrow("turn_sharp_left") {
            stroked(TURN_STROKE) {
                moveTo(38f, 58f)
                lineTo(38f, 38f)
                arcTo(9f, 9f, 0f, false, false, 29f, 29f)
                lineTo(16f, 29f)
            }
            filled {
                moveTo(4f, 29f)
                lineTo(16f, 18f)
                lineTo(16f, 40f)
                close()
            }
        }

    val TurnUturn =
        turnArrow("turn_uturn") {
            stroked(TURN_STROKE) {
                moveTo(44f, 58f)
                lineTo(44f, 30f)
                arcTo(12f, 12f, 0f, false, false, 20f, 30f)
                lineTo(20f, 43f)
            }
            filled {
                moveTo(20f, 56f)
                lineTo(8f, 42f)
                lineTo(32f, 42f)
                close()
            }
        }

    val TurnRoundabout =
        turnArrow("turn_roundabout") {
            stroked(TURN_STROKE) {
                moveTo(32f, 58f)
                lineTo(32f, 48f)
            }
            stroked(TURN_STROKE) {
                moveTo(48f, 32f)
                arcTo(16f, 16f, 0f, true, false, 16f, 32f)
                arcTo(16f, 16f, 0f, true, false, 48f, 32f)
            }
            filled {
                moveTo(56f, 32f)
                lineTo(44f, 24f)
                lineTo(44f, 40f)
                close()
            }
        }

    val TurnArrive =
        turnArrow("turn_arrive") {
            stroked(TURN_STROKE) {
                moveTo(18f, 58f)
                lineTo(18f, 10f)
            }
            filled {
                moveTo(18f, 10f)
                lineTo(50f, 10f)
                lineTo(41f, 21f)
                lineTo(50f, 32f)
                lineTo(18f, 32f)
                close()
            }
        }

    val TurnReroute =
        turnArrow("turn_reroute") {
            stroked(TURN_STROKE) {
                moveTo(14f, 51f)
                lineTo(14f, 24f)
                arcTo(11f, 11f, 0f, false, true, 25f, 13f)
                lineTo(49f, 13f)
                moveTo(40f, 5f)
                lineTo(52f, 13f)
                lineTo(40f, 21f)
                moveTo(51f, 40f)
                lineTo(51f, 51f)
                lineTo(40f, 51f)
            }
        }

    val TurnPause =
        turnArrow("turn_pause") {
            stroked(TURN_STROKE) {
                moveTo(24f, 14f)
                lineTo(24f, 50f)
                moveTo(40f, 14f)
                lineTo(40f, 50f)
            }
        }
}

/** The drawn icon for every [NavigationTurnArrow] the app can emit. */
fun turnArrowIcon(arrow: NavigationTurnArrow): ImageVector =
    when (arrow) {
        NavigationTurnArrow.Straight -> ShellIcons.TurnStraight
        NavigationTurnArrow.SlightLeft -> ShellIcons.TurnSlightLeft
        NavigationTurnArrow.Left -> ShellIcons.TurnLeft
        NavigationTurnArrow.SharpLeft -> ShellIcons.TurnSharpLeft
        NavigationTurnArrow.Uturn -> ShellIcons.TurnUturn
        NavigationTurnArrow.SlightRight -> ShellIcons.TurnSlightRight
        NavigationTurnArrow.Right -> ShellIcons.TurnRight
        NavigationTurnArrow.SharpRight -> ShellIcons.TurnSharpRight
        NavigationTurnArrow.Roundabout -> ShellIcons.TurnRoundabout
        NavigationTurnArrow.Arrive -> ShellIcons.TurnArrive
        NavigationTurnArrow.Reroute -> ShellIcons.TurnReroute
        NavigationTurnArrow.Pause -> ShellIcons.TurnPause
    }

/** Tints a decorative shell icon and keeps it out of the spoken tree. */
@Composable
fun ShellIcon(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Image(
        imageVector = icon,
        contentDescription = null,
        modifier = modifier.clearAndSetSemantics {},
        colorFilter = ColorFilter.tint(tint),
    )
}

private val FILL = SolidColor(Color.White)
private const val ICON_STROKE = 2f
private const val TURN_STROKE = 7f
private const val DIM_ALPHA = 0.45f
private const val ICON_SIZE_DP = 24
private const val TURN_SIZE_DP = 64

private fun icon(
    name: String,
    size: Dp = ICON_SIZE_DP.dp,
    build: ImageVector.Builder.() -> Unit,
): ImageVector =
    ImageVector
        .Builder(
            name = name,
            defaultWidth = size,
            defaultHeight = size,
            viewportWidth = ICON_SIZE_DP.toFloat(),
            viewportHeight = ICON_SIZE_DP.toFloat(),
        ).apply(build)
        .build()

private fun turnArrow(
    name: String,
    build: ImageVector.Builder.() -> Unit,
): ImageVector =
    ImageVector
        .Builder(
            name = name,
            defaultWidth = TURN_SIZE_DP.dp,
            defaultHeight = TURN_SIZE_DP.dp,
            viewportWidth = TURN_SIZE_DP.toFloat(),
            viewportHeight = TURN_SIZE_DP.toFloat(),
        ).apply(build)
        .build()

private fun ImageVector.Builder.filled(block: PathBuilder.() -> Unit) {
    path(fill = FILL) { block() }
}

private fun ImageVector.Builder.stroked(
    width: Float = ICON_STROKE,
    block: PathBuilder.() -> Unit,
) {
    path(
        stroke = FILL,
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) { block() }
}

private fun PathBuilder.circle(
    cx: Float,
    cy: Float,
    r: Float,
) {
    moveTo(cx - r, cy)
    arcTo(r, r, 0f, true, true, cx + r, cy)
    arcTo(r, r, 0f, true, true, cx - r, cy)
}
