package nl.r1a.grassmaps.plugins.downloads.ui

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
import nl.r1a.grassmaps.plugin.api.model.DownloadRowStatus
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.RegionOutline
import nl.r1a.grassmaps.plugin.ui.api.LocalShellColors
import nl.r1a.grassmaps.plugin.ui.api.ShellColors
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

private const val INSTALLED_ALPHA = 0.55f
private const val PENDING_ALPHA = 0.3f
private const val FAILED_ALPHA = 0.3f

internal const val LEGEND_ON_THIS_PHONE = "downloads_on_this_phone"
internal const val LEGEND_UPDATING = "downloads_legend_updating"
internal const val LEGEND_UPDATE_READY = "downloads_legend_update_ready"
internal const val LEGEND_NEAR_YOU = "downloads_near_you"
internal const val LEGEND_AVAILABLE = "downloads_available"
internal const val LEGEND_YOU = "downloads_legend_you"

/** Every colour the mini-map paints, so the legend swatches cannot invent one. */
internal enum class MiniMapPaint {
    INSTALLED,
    PENDING,
    FAILED,
    AVAILABLE,
    SELECTED,
    ;

    fun color(colors: ShellColors): Color =
        when (this) {
            INSTALLED -> colors.accent.copy(alpha = INSTALLED_ALPHA)
            PENDING -> colors.accent.copy(alpha = PENDING_ALPHA)
            FAILED -> colors.danger.copy(alpha = FAILED_ALPHA)
            AVAILABLE -> colors.line
            SELECTED -> colors.ink
        }
}

/** One label in the mini-map's key: a string key for tests and a paint for the swatch. */
internal data class MiniMapLegendEntry(
    val labelKey: String,
    val paint: MiniMapPaint,
)

/**
 * The six states the map's key names, in the design's order. Updating and
 * update ready share the one pending tint, and the two position labels share
 * the selected outline: those are the only colours the map paints for them.
 */
internal val miniMapLegend =
    listOf(
        MiniMapLegendEntry(LEGEND_ON_THIS_PHONE, MiniMapPaint.INSTALLED),
        MiniMapLegendEntry(LEGEND_UPDATING, MiniMapPaint.PENDING),
        MiniMapLegendEntry(LEGEND_UPDATE_READY, MiniMapPaint.PENDING),
        MiniMapLegendEntry(LEGEND_NEAR_YOU, MiniMapPaint.SELECTED),
        MiniMapLegendEntry(LEGEND_AVAILABLE, MiniMapPaint.AVAILABLE),
        MiniMapLegendEntry(LEGEND_YOU, MiniMapPaint.SELECTED),
    )

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
    val colors = LocalShellColors.current
    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(MAP_HEIGHT_DP.dp)
                .clip(RoundedCornerShape(MAP_CORNER_DP.dp))
                .background(colors.surface)
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
            drawPath(path, fillFor(statuses[outline.region], colors))
            drawPath(path, colors.surface, style = Stroke(width = BORDER_STROKE_PX))
        }
        outlines.firstOrNull { it.region == selectedRegion }?.let { outline ->
            drawPath(outlinePath(outline, projection), colors.ink, style = Stroke(width = SELECTED_STROKE_PX))
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

internal fun fillFor(
    status: DownloadRowStatus?,
    colors: ShellColors,
): Color =
    when (status) {
        DownloadRowStatus.INSTALLED -> MiniMapPaint.INSTALLED.color(colors)
        DownloadRowStatus.UPDATE_AVAILABLE, DownloadRowStatus.DOWNLOADING -> MiniMapPaint.PENDING.color(colors)
        DownloadRowStatus.FAILED -> MiniMapPaint.FAILED.color(colors)
        DownloadRowStatus.AVAILABLE, null -> MiniMapPaint.AVAILABLE.color(colors)
    }
