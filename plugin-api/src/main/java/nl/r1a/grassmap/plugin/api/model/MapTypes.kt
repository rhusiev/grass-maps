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
 *
 * Edges are inclusive: a point exactly on an edge is inside the box, which is
 * what a viewport filter must do at the four screen edges (T-0206).
 */
data class BoundingBox(
    val southWest: GeoPoint,
    val northEast: GeoPoint,
) {
    /** True when [point] lies on or inside this box. */
    fun contains(point: GeoPoint): Boolean =
        point.latitude in southWest.latitude..northEast.latitude &&
            point.longitude in southWest.longitude..northEast.longitude

    /**
     * True when the corners are finite, inside WGS84 and ordered, so the box
     * can be used as a filter.
     */
    fun isValid(): Boolean {
        val south = southWest.latitude
        val west = southWest.longitude
        val north = northEast.latitude
        val east = northEast.longitude
        return south.isFinite() &&
            west.isFinite() &&
            north.isFinite() &&
            east.isFinite() &&
            south in -MAX_LATITUDE..MAX_LATITUDE &&
            north in -MAX_LATITUDE..MAX_LATITUDE &&
            west in -MAX_LONGITUDE..MAX_LONGITUDE &&
            east in -MAX_LONGITUDE..MAX_LONGITUDE &&
            south <= north &&
            west <= east
    }
}

private const val MAX_LATITUDE: Double = 90.0
private const val MAX_LONGITUDE: Double = 180.0

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
