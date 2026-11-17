package nl.r1a.grassmap.plugins.downloads.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import nl.r1a.grassmap.plugin.api.model.DownloadRowStatus
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RegionOutline
import nl.r1a.grassmap.plugin.ui.api.NavigationScreenColors
import kotlin.math.cos
import kotlin.math.min

/**
 * Where each border falls on a canvas of [width] by [height] pixels. The map is
 * an equirectangular fit: longitude is scaled by the cosine of the middle
 * latitude, so an oblast keeps its shape, and the whole set is centred. A tap
 * is turned back into a position with [toGeo] and hit-tested against the
 * borders, so the screen needs no second source of shapes.
 */
internal class MiniMapProjection(
    outlines: List<RegionOutline>,
    private val width: Float,
    private val height: Float,
) {
    private val west: Double
    private val north: Double
    private val longitudeScale: Double
    private val pixelsPerDegree: Double
    private val offsetX: Double
    private val offsetY: Double
    val isEmpty: Boolean

    init {
        val boxes = outlines.mapNotNull { it.bounds }
        isEmpty = boxes.isEmpty() || width <= 0f || height <= 0f
        west = boxes.minOfOrNull { it.southWest.longitude } ?: 0.0
        north = boxes.maxOfOrNull { it.northEast.latitude } ?: 0.0
        val east = boxes.maxOfOrNull { it.northEast.longitude } ?: 0.0
        val south = boxes.minOfOrNull { it.southWest.latitude } ?: 0.0
        longitudeScale = cos(Math.toRadians((north + south) / 2.0))
        val spanX = ((east - west) * longitudeScale).coerceAtLeast(MIN_SPAN_DEGREES)
        val spanY = (north - south).coerceAtLeast(MIN_SPAN_DEGREES)
        pixelsPerDegree = min((width - 2 * PADDING_PX) / spanX, (height - 2 * PADDING_PX) / spanY)
        offsetX = (width - spanX * pixelsPerDegree) / 2.0
        offsetY = (height - spanY * pixelsPerDegree) / 2.0
    }

    fun toScreen(point: GeoPoint): Offset =
        Offset(
            (offsetX + (point.longitude - west) * longitudeScale * pixelsPerDegree).toFloat(),
            (offsetY + (north - point.latitude) * pixelsPerDegree).toFloat(),
        )

    fun toGeo(position: Offset): GeoPoint =
        GeoPoint(
            north - (position.y - offsetY) / pixelsPerDegree,
            west + (position.x - offsetX) / (pixelsPerDegree * longitudeScale),
        )

    /** The region whose border holds [position], or null when the tap fell between borders. */
    fun regionAt(
        outlines: List<RegionOutline>,
        position: Offset,
    ): String? {
        if (isEmpty) {
            return null
        }
        val point = toGeo(position)
        return outlines.firstOrNull { it.contains(point) }?.region
    }

    private companion object {
        const val PADDING_PX = 8f
        const val MIN_SPAN_DEGREES = 1e-6
    }
}

private val STATUS_GREEN = Color(NavigationScreenColors.GREEN)
private val INSTALLED_FILL = STATUS_GREEN.copy(alpha = 0.55f)
private val PENDING_FILL = STATUS_GREEN.copy(alpha = 0.30f)
private val FAILED_FILL = Color(0x4DB3261E)
private val OTHER_FILL = Color(0xFFE3E1DC)
private val SELECTED_STROKE = Color(0xFF1F2329)
private val BORDER_STROKE = Color(0xFFFFFFFF)
private val MAP_BACKGROUND = Color(0xFFFFFFFF)

private const val MAP_HEIGHT_DP = 200
private const val MAP_CORNER_DP = 16
private const val BORDER_STROKE_PX = 1.5f
private const val SELECTED_STROKE_PX = 4f

/**
 * The regions as outlines, coloured by what each one is doing, one tap away from
 * its sheet. This is the one press above the thumb: the layout rule exempts
 * tapping the map at the top of the screen.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun RegionsMiniMap(
    outlines: List<RegionOutline>,
    statuses: Map<String, DownloadRowStatus>,
    selectedRegion: String?,
    description: String,
    onTapRegion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(MAP_HEIGHT_DP.dp)
                .clip(RoundedCornerShape(MAP_CORNER_DP.dp))
                .background(MAP_BACKGROUND)
                .semantics { contentDescription = description }
                .pointerInput(outlines) {
                    detectTapGestures { position ->
                        val projection = MiniMapProjection(outlines, size.width.toFloat(), size.height.toFloat())
                        projection.regionAt(outlines, position)?.let(onTapRegion)
                    }
                },
    ) {
        val projection = MiniMapProjection(outlines, size.width, size.height)
        for (outline in outlines) {
            val path = outlinePath(outline, projection)
            drawPath(path, fillFor(statuses[outline.region]))
            drawPath(path, BORDER_STROKE, style = Stroke(width = BORDER_STROKE_PX))
        }
        outlines.firstOrNull { it.region == selectedRegion }?.let { outline ->
            drawPath(outlinePath(outline, projection), SELECTED_STROKE, style = Stroke(width = SELECTED_STROKE_PX))
        }
    }
}

private fun outlinePath(
    outline: RegionOutline,
    projection: MiniMapProjection,
): Path =
    Path().apply {
        for (ring in outline.polygons) {
            ring.forEachIndexed { index, point ->
                val position = projection.toScreen(point)
                if (index == 0) moveTo(position.x, position.y) else lineTo(position.x, position.y)
            }
            close()
        }
    }

private fun fillFor(status: DownloadRowStatus?): Color =
    when (status) {
        DownloadRowStatus.INSTALLED -> INSTALLED_FILL
        DownloadRowStatus.UPDATE_AVAILABLE, DownloadRowStatus.DOWNLOADING -> PENDING_FILL
        DownloadRowStatus.FAILED -> FAILED_FILL
        DownloadRowStatus.AVAILABLE, null -> OTHER_FILL
    }
