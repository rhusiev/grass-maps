package nl.r1a.grassmap.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maplibre.compose.map.MapStyleState
import org.maplibre.compose.overlay.AttributionLinks
import org.maplibre.compose.overlay.attributions

/**
 * The map's visible credit: one line per source that declares an attribution,
 * including a raster tile source a plugin adds (T-0512). The OSM policy asks
 * for a visible credit, so this is text on the map and not a hidden info
 * button. A style with no attributed source draws nothing.
 *
 * The engine draws its own mark in the bottom-left corner and its native
 * attribution at the bottom end, so the credit sits above the engine's mark
 * rather than on top of it: the T-0514 golden run showed the pill covering
 * both.
 *
 * This is ordinary Compose content drawn over the map, never a node inside it.
 * The map's content lambda accepts map nodes only, so a layout emitted there
 * crashes the applier with `LayoutNode cannot be cast to MapNode` (cadence 38).
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun MapAttributionOverlay(
    style: MapStyleState,
    modifier: Modifier = Modifier,
) {
    val attributions = style.attributions()
    if (attributions.isEmpty()) return
    Box(modifier = modifier.fillMaxSize()) {
        AttributionLinks(
            attributions = attributions,
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = ATTRIBUTION_MARGIN_DP.dp,
                        end = ATTRIBUTION_MARGIN_DP.dp,
                        bottom = ATTRIBUTION_BOTTOM_INSET_DP.dp,
                    ).background(ATTRIBUTION_BACKGROUND, RoundedCornerShape(ATTRIBUTION_CORNER_DP.dp))
                    .padding(
                        horizontal = ATTRIBUTION_HORIZONTAL_PADDING_DP.dp,
                        vertical = ATTRIBUTION_VERTICAL_PADDING_DP.dp,
                    ),
            textStyle = TextStyle(color = Color.White, fontSize = ATTRIBUTION_FONT_SP.sp),
        )
    }
}

// The engine draws its logo in the bottom-left corner: about 24 dp tall with a
// 4 dp margin on a 420 dpi device. This inset clears the mark and its margin, so
// the two credits stack instead of one covering the other.
private const val ATTRIBUTION_BOTTOM_INSET_DP = 40

private val ATTRIBUTION_BACKGROUND = Color(0x99000000)
private const val ATTRIBUTION_FONT_SP = 10
private const val ATTRIBUTION_MARGIN_DP = 4
private const val ATTRIBUTION_CORNER_DP = 4
private const val ATTRIBUTION_HORIZONTAL_PADDING_DP = 6
private const val ATTRIBUTION_VERTICAL_PADDING_DP = 2
