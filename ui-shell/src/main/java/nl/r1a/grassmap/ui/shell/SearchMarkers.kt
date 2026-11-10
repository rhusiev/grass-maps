package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.ScreenOffset
import nl.r1a.grassmap.plugin.api.model.WebMercator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/** Where one search marker draws, in density-independent pixels. */
data class SearchMarkerOffset(
    val xDp: Float,
    val yDp: Float,
    val visible: Boolean,
)

/**
 * Projects a search marker onto the map view (T-0203).
 *
 * The projection is the map's own model: Web-Mercator world pixels at the
 * camera zoom, from [WebMercator]'s two constants, so there is no second Earth
 * model and no private metres-per-pixel value. The delta from the camera centre
 * is rotated by the camera bearing, which is how a point east of the centre
 * draws at the top when the map faces east.
 *
 * Tilt is not applied. The shipped app never tilts the camera; only the debug
 * harness can, and a marker overlay under tilt would be wrong rather than
 * approximate. When the app gains a tilting camera mode, markers belong in the
 * engine's marker slot, not in this overlay.
 *
 * The anchor is the point the camera centre draws at. With a position offset,
 * MapLibre pads the viewport so the puck sits off-centre, so the overlay is
 * shifted by the same [ScreenOffset] the host was given.
 */
object SearchMarkerProjection {
    /** The latitude a Web-Mercator square ends at; above it the projection is infinite. */
    const val MAX_MERCATOR_LATITUDE: Double = 85.05112878

    /** How far outside the view a marker may sit and still be drawn. */
    const val VISIBLE_MARGIN_DP: Float = 24f

    fun offset(
        position: GeoPoint,
        camera: Camera,
        viewportWidthDp: Float,
        viewportHeightDp: Float,
        anchor: ScreenOffset = ScreenOffset(0f, 0f),
    ): SearchMarkerOffset {
        if (!viewportWidthDp.isFinite() || !viewportHeightDp.isFinite() || viewportWidthDp <= 0f || viewportHeightDp <= 0f) {
            return HIDDEN
        }
        val zoom = if (camera.zoom.isFinite()) camera.zoom.coerceIn(0.0, MAX_ZOOM) else 0.0
        val centre = worldPixels(camera.center, zoom)
        val target = worldPixels(position, zoom)
        val dx = target.first - centre.first
        val dy = target.second - centre.second
        val bearing = if (camera.bearing.isFinite()) camera.bearing * PI / HALF_TURN_DEGREES else 0.0
        val rotatedX = dx * cos(bearing) + dy * sin(bearing)
        val rotatedY = -dx * sin(bearing) + dy * cos(bearing)
        val x = viewportWidthDp / 2.0 + rotatedX + anchor.x
        val y = viewportHeightDp / 2.0 + rotatedY + anchor.y
        if (!x.isFinite() || !y.isFinite()) {
            return HIDDEN
        }
        val margin = VISIBLE_MARGIN_DP.toDouble()
        val visible =
            x >= -margin && x <= viewportWidthDp + margin &&
                y >= -margin && y <= viewportHeightDp + margin
        return SearchMarkerOffset(xDp = x.toFloat(), yDp = y.toFloat(), visible = visible)
    }

    private fun worldPixels(
        point: GeoPoint,
        zoom: Double,
    ): Pair<Double, Double> {
        val size = WebMercator.TILE_SIZE_PX * 2.0.pow(zoom)
        val worldX = (point.longitude.coerceIn(-HALF_TURN_DEGREES, HALF_TURN_DEGREES) + HALF_TURN_DEGREES) / FULL_TURN_DEGREES * size
        val latitude = point.latitude.coerceIn(-MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE) * PI / HALF_TURN_DEGREES
        val sine = sin(latitude)
        val worldY = (0.5 - ln((1 + sine) / (1 - sine)) / (2.0 * PI)) * size
        return worldX to worldY
    }

    private val HIDDEN = SearchMarkerOffset(xDp = 0f, yDp = 0f, visible = false)

    private const val MAX_ZOOM: Double = 24.0
    private const val HALF_TURN_DEGREES: Double = 180.0
    private const val FULL_TURN_DEGREES: Double = 360.0
}
