package nl.r1a.grassmap.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmap.plugin.api.model.MapCredits
import nl.r1a.grassmap.plugin.ui.api.LocalShellColors
import nl.r1a.grassmap.plugin.ui.api.ShellColors
import org.maplibre.compose.map.MapStyleState
import org.maplibre.compose.overlay.AttributionLinks
import org.maplibre.compose.overlay.attributions

/**
 * The credits to draw: those of the style's sources, which an app-supplied
 * vector source cannot hand to the engine, then the engine's own (raster
 * sources). An engine credit that names OpenStreetMap becomes the one
 * [MapCredits.OPENSTREETMAP] entry, and one that names the Copernicus DEM or GLO-30
 * becomes [MapCredits.COPERNICUS_DEM]. An EOX credit stays whole: it names
 * "Copernicus Sentinel data", which is not the DEM. A credit several sources share is drawn
 * once.
 */
internal fun mergeCredits(
    sourceCredits: List<String>,
    engineCredits: List<String>,
): List<String> =
    (sourceCredits + engineCredits)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map(::normaliseCredit)
        .distinct()

internal fun normaliseCredit(credit: String): String =
    when {
        credit.contains(MapCredits.EOX_NAME) -> credit
        credit.contains(OPENSTREETMAP_NAME, ignoreCase = true) -> MapCredits.OPENSTREETMAP
        COPERNICUS_NAMES.any { credit.contains(it, ignoreCase = true) } -> MapCredits.COPERNICUS_DEM
        else -> credit
    }

/**
 * The one short line shown on the map: the plain text of each credit, with the
 * two known credits cut to their short forms. The full texts and links sit
 * behind a tap on the line.
 */
internal fun collapsedLabel(credits: List<String>): String =
    credits.joinToString(COLLAPSED_SEPARATOR) { credit ->
        when (credit) {
            MapCredits.OPENSTREETMAP -> OPENSTREETMAP_SHORT
            MapCredits.COPERNICUS_DEM -> COPERNICUS_SHORT
            MapCredits.EOX_2016, MapCredits.EOX_2018 -> MapCredits.EOX_NAME
            else -> credit.replace(HTML_TAG, "").trim()
        }
    }

/**
 * The map's visible credit: one short line, "(c) OpenStreetMap contributors"
 * plus "Copernicus" when contours are on. The OSM policy asks for a visible
 * credit, so this is text on the map and not a hidden info button. A tap on the
 * line opens a card with every full credit and its links, and a second tap
 * closes it. A style with no attributed source draws nothing.
 *
 * This is the only credit surface. The engine's own attribution button is off
 * (`MaplibreMap` draws only its logo, see `MapSurfaceView`), so the credits
 * never appear twice. The line sits at the bottom start, above the engine's
 * logo and clear of the app's scale bar at the bottom end. [bottomInset] is
 * how far the app's bottom bars and sheets reach up from the window edge: the
 * line rises above them, so a trip bar or a route sheet never hides it. The
 * top row is not free: the search field, the selected-point label and the
 * navigation turn card all use it.
 *
 * This is ordinary Compose content drawn over the map, never a node inside it.
 * The map's content lambda accepts map nodes only, so a layout emitted there
 * crashes the applier with `LayoutNode cannot be cast to MapNode` (cadence 38).
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun MapAttributionOverlay(
    style: MapStyleState,
    sourceCredits: List<String>,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
) {
    val credits = mergeCredits(sourceCredits, style.attributions())
    if (credits.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val colors = LocalShellColors.current
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = ATTRIBUTION_MARGIN_DP.dp,
                        end = ATTRIBUTION_END_CLEARANCE_DP.dp,
                        bottom = maxOf(ATTRIBUTION_BOTTOM_INSET_DP.dp, bottomInset + ATTRIBUTION_CLEARANCE_DP.dp),
                    ),
        ) {
            if (expanded) {
                AttributionLinks(
                    attributions = credits,
                    modifier = creditSurface(colors),
                    textStyle = creditTextStyle(colors),
                )
            }
            BasicText(
                text = collapsedLabel(credits),
                style = creditTextStyle(colors),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    creditSurface(colors).clickable(role = Role.Button) { expanded = !expanded },
            )
        }
    }
}

private fun creditTextStyle(colors: ShellColors) = TextStyle(color = colors.ink, fontSize = ATTRIBUTION_FONT_SP.sp)

private fun creditSurface(colors: ShellColors): Modifier =
    Modifier
        .background(colors.glass, RoundedCornerShape(ATTRIBUTION_CORNER_DP.dp))
        .border(1.dp, colors.ring, RoundedCornerShape(ATTRIBUTION_CORNER_DP.dp))
        .padding(horizontal = ATTRIBUTION_HORIZONTAL_PADDING_DP.dp, vertical = ATTRIBUTION_VERTICAL_PADDING_DP.dp)

private const val OPENSTREETMAP_NAME = "OpenStreetMap"
private val COPERNICUS_NAMES = listOf("Copernicus DEM", "GLO-30")
private const val OPENSTREETMAP_SHORT = "\u00A9 OpenStreetMap contributors"
private const val COPERNICUS_SHORT = "Copernicus"
private const val COLLAPSED_SEPARATOR = " \u00B7 "
private val HTML_TAG = Regex("<[^>]*>")

// The engine draws its logo in the bottom-left corner: about 24 dp tall with a
// 4 dp margin on a 420 dp-per-inch device. This inset clears the mark and its
// margin, so the credit sits above it instead of covering it.
private const val ATTRIBUTION_BOTTOM_INSET_DP = 40

// The app's scale bar sits at the bottom end, about 90 dp wide with its margin.
// The credit line stops before it, so a long line never runs under the bar.
private const val ATTRIBUTION_END_CLEARANCE_DP = 100

// The gap between the credit line and the bar or sheet it rises above.
private const val ATTRIBUTION_CLEARANCE_DP = 4

private const val ATTRIBUTION_FONT_SP = 10
private const val ATTRIBUTION_MARGIN_DP = 4
private const val ATTRIBUTION_CORNER_DP = 4
private const val ATTRIBUTION_HORIZONTAL_PADDING_DP = 6
private const val ATTRIBUTION_VERTICAL_PADDING_DP = 2
