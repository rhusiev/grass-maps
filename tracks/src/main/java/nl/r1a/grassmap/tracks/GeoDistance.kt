package nl.r1a.grassmap.tracks

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The haversine ground distance between two positions, with the IUGG mean Earth
 * radius. The statistics and the elevation profile measure the same steps, so
 * one function serves both: a chart that used another constant would drift from
 * the totals beside it.
 *
 * The app also measures a followed track's walk to its join point with it
 * (T-0412), so the hint a start sheet shows and the analysis totals beside it
 * agree.
 */
fun distanceMeters(
    from: GeoPoint,
    to: GeoPoint,
): Double {
    val fromLatitude = Math.toRadians(from.latitude)
    val toLatitude = Math.toRadians(to.latitude)
    val latitudeDelta = toLatitude - fromLatitude
    val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
    val haversine =
        sin(latitudeDelta / 2.0) * sin(latitudeDelta / 2.0) +
            cos(fromLatitude) * cos(toLatitude) *
            sin(longitudeDelta / 2.0) * sin(longitudeDelta / 2.0)
    return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
}

/** IUGG mean Earth radius. The oracle documents the same constant. */
internal const val EARTH_RADIUS_METERS: Double = 6_371_008.8
