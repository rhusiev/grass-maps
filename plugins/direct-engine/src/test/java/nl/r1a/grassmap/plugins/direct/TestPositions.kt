package nl.r1a.grassmap.plugins.direct

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The haversine definition written a second time, with the atan2 form instead
 * of the engine's asin form, so a length test never compares the engine with
 * itself.
 */
internal fun haversineMetres(
    from: GeoPoint,
    to: GeoPoint,
): Double {
    val fromLatitude = Math.toRadians(from.latitude)
    val toLatitude = Math.toRadians(to.latitude)
    val latitudeDelta = toLatitude - fromLatitude
    val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
    val a =
        sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(fromLatitude) * cos(toLatitude) * sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
    return 2 * EARTH_RADIUS_METRES * atan2(sqrt(a), sqrt(1.0 - a))
}

internal const val EARTH_RADIUS_METRES: Double = 6_371_000.0

/** Two real pairs: one about 340 km apart, one about 117 km apart. */
internal val LVIV: GeoPoint = GeoPoint(latitude = 49.8397, longitude = 24.0297)

internal val WARSAW: GeoPoint = GeoPoint(latitude = 52.2297, longitude = 21.0122)

internal val TERNOPIL: GeoPoint = GeoPoint(latitude = 49.5535, longitude = 25.5948)
