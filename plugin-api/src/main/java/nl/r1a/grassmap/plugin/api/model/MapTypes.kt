package nl.r1a.grassmap.plugin.api.model

import kotlinx.serialization.Serializable

/**
 * A geographic point in WGS84 degrees.
 */
@Serializable
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)

/**
 * A north-up geographic rectangle.
 */
data class BoundingBox(
    val southWest: GeoPoint,
    val northEast: GeoPoint,
)

/**
 * The visible camera state. The map host emits it; widgets sample it.
 */
data class Camera(
    val center: GeoPoint,
    val zoom: Double,
    val bearing: Double = 0.0,
    val tilt: Double = 0.0,
)

/**
 * A camera change. A null field keeps the current value.
 */
data class CameraUpdate(
    val center: GeoPoint? = null,
    val zoom: Double? = null,
    val bearing: Double? = null,
    val tilt: Double? = null,
)

/**
 * A screen-space offset in density-independent pixels.
 */
data class ScreenOffset(
    val x: Float,
    val y: Float,
)

/**
 * How the camera follows a position.
 */
enum class TrackingMode {
    Free,
    Follow,
    FollowWithHeading,
}
