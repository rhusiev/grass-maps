package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.plugin.api.model.BoundingBox
import nl.r1a.grassmaps.plugin.api.model.Camera
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.WebMercator
import kotlin.math.cos

/** The north-up box around [points], or null when there is nothing to fit. */
internal fun boundsOf(points: List<GeoPoint>?): BoundingBox? {
    if (points.isNullOrEmpty()) {
        return null
    }
    return BoundingBox(
        southWest = GeoPoint(points.minOf { it.latitude }, points.minOf { it.longitude }),
        northEast = GeoPoint(points.maxOf { it.latitude }, points.maxOf { it.longitude }),
    )
}

private const val METERS_PER_DEGREE = 111_320.0
private const val MAX_LATITUDE = 85.0
private const val HALF = 2.0

/**
 * The north-up box a north-up screen of [widthDp] by [heightDp] shows around
 * [camera]. The map lays out in dp, so the ground scale is metres per dp.
 */
internal fun viewportBounds(
    camera: Camera,
    widthDp: Int,
    heightDp: Int,
): BoundingBox {
    val metersPerDp = WebMercator.metersPerPixel(camera.center.latitude, camera.zoom)
    val halfLat = metersPerDp * heightDp / HALF / METERS_PER_DEGREE
    val latScale = cos(Math.toRadians(camera.center.latitude)).coerceAtLeast(MIN_COS)
    val halfLon = metersPerDp * widthDp / HALF / (METERS_PER_DEGREE * latScale)
    val south = (camera.center.latitude - halfLat).coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
    val north = (camera.center.latitude + halfLat).coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
    val west = (camera.center.longitude - halfLon).coerceIn(-MAX_LONGITUDE, MAX_LONGITUDE)
    val east = (camera.center.longitude + halfLon).coerceIn(-MAX_LONGITUDE, MAX_LONGITUDE)
    return BoundingBox(GeoPoint(south, west), GeoPoint(north, east))
}

private const val MIN_COS = 0.01
private const val MAX_LONGITUDE = 180.0
