package nl.r1a.grassmap.navigation

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Small spherical helpers shared by the locator and the replay builder. */
internal object GeoMath {
    const val EARTH_RADIUS_METERS: Double = 6_371_000.0

    /** The great-circle distance between two points, in metres. */
    fun distanceMeters(
        from: GeoPoint,
        to: GeoPoint,
    ): Double {
        val fromLat = Math.toRadians(from.latitude)
        val toLat = Math.toRadians(to.latitude)
        val deltaLat = toLat - fromLat
        val deltaLon = Math.toRadians(to.longitude - from.longitude)
        val a = sin(deltaLat / 2) * sin(deltaLat / 2) + cos(fromLat) * cos(toLat) * sin(deltaLon / 2) * sin(deltaLon / 2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** The initial bearing from [from] to [to], in degrees clockwise from north. */
    fun bearingDegrees(
        from: GeoPoint,
        to: GeoPoint,
    ): Double {
        val fromLat = Math.toRadians(from.latitude)
        val toLat = Math.toRadians(to.latitude)
        val deltaLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(deltaLon) * cos(toLat)
        val x = cos(fromLat) * sin(toLat) - sin(fromLat) * cos(toLat) * cos(deltaLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Moves [origin] [distanceMeters] along [bearingDegrees]. */
    fun offset(
        origin: GeoPoint,
        bearingDegrees: Double,
        distanceMeters: Double,
    ): GeoPoint {
        val angular = distanceMeters / EARTH_RADIUS_METERS
        val bearing = Math.toRadians(bearingDegrees)
        val latitude = Math.toRadians(origin.latitude)
        val longitude = Math.toRadians(origin.longitude)
        val targetLat = Math.asin(sin(latitude) * cos(angular) + cos(latitude) * sin(angular) * cos(bearing))
        val targetLon =
            longitude +
                atan2(
                    sin(bearing) * sin(angular) * cos(latitude),
                    cos(angular) - sin(latitude) * sin(targetLat),
                )
        return GeoPoint(
            latitude = Math.toDegrees(targetLat),
            longitude = normalizeLongitude(Math.toDegrees(targetLon)),
        )
    }

    private fun normalizeLongitude(degrees: Double): Double {
        val wrapped = (degrees + 540.0) % 360.0
        return wrapped - 180.0
    }
}

/**
 * Snaps a position to a route line in a local equirectangular plane. Both the
 * polyline and the reported distances are in metres, so the radius of the
 * target latitude only affects the longitude scale.
 *
 * The locator is the one measure of a line in this module: the follower snaps
 * every fix with it, and [trackFollowPlan] and [trackFollowRoute] choose a
 * followed track's join and cut its line with it, so a followed track's
 * distances are the same space the follower reports.
 */
class PolylineLocator(
    points: List<GeoPoint>,
) {
    private val vertices: List<GeoPoint> = points
    private val cumulative: DoubleArray
    private val segments: List<Segment>
    private val totalMeters: Double

    init {
        val cumulativeLengths = DoubleArray(points.size)
        var running = 0.0
        for (index in 1 until points.size) {
            running += GeoMath.distanceMeters(points[index - 1], points[index])
            cumulativeLengths[index] = running
        }
        cumulative = cumulativeLengths
        val latitudeScale = cos(Math.toRadians(points.firstOrNull()?.latitude ?: 0.0))
        segments =
            (1 until points.size).map { index ->
                Segment(
                    start = points[index - 1],
                    end = points[index],
                    startMeters = cumulative[index - 1],
                    meters = cumulative[index] - cumulative[index - 1],
                    latitudeScale = latitudeScale.coerceAtLeast(MIN_LATITUDE_SCALE),
                )
            }
        totalMeters = running
    }

    /** The distance along the line, in metres. */
    fun totalMeters(): Double = totalMeters

    /** The closest point on the line, or null when the line has fewer than two points. */
    fun locate(point: GeoPoint): Projection? {
        if (segments.isEmpty()) {
            return null
        }
        var best: Projection? = null
        for (segment in segments) {
            val candidate = segment.project(point)
            if (best == null || candidate.distanceMeters < best.distanceMeters) {
                best = candidate
            }
        }
        return best?.let { projection ->
            projection.copy(
                remainingMeters = (totalMeters - projection.alongMeters).coerceIn(0.0, totalMeters),
            )
        }
    }

    /**
     * The point [distanceMeters] from the start of the line, interpolated along
     * the segment that contains it.
     */
    fun pointAt(distanceMeters: Double): GeoPoint? {
        if (vertices.isEmpty()) {
            return null
        }
        if (vertices.size == 1 || distanceMeters <= 0.0) {
            return vertices.first()
        }
        if (distanceMeters >= totalMeters) {
            return vertices.last()
        }
        val segment = segments.firstOrNull { distanceMeters <= it.startMeters + it.meters } ?: return vertices.last()
        val fraction = ((distanceMeters - segment.startMeters) / segment.meters).coerceIn(0.0, 1.0)
        return GeoPoint(
            latitude = segment.start.latitude + (segment.end.latitude - segment.start.latitude) * fraction,
            longitude = segment.start.longitude + (segment.end.longitude - segment.start.longitude) * fraction,
        )
    }

    /**
     * The route vertices between two distances, with an interpolated point at
     * each end. Always returns at least two points when the line has any.
     */
    fun pointsBetween(
        startMeters: Double,
        endMeters: Double,
    ): List<GeoPoint> {
        if (vertices.isEmpty()) {
            return emptyList()
        }
        val start = startMeters.coerceIn(0.0, totalMeters)
        val end = endMeters.coerceIn(start, totalMeters)
        val result = mutableListOf<GeoPoint>()
        pointAt(start)?.let(result::add)
        for (index in vertices.indices) {
            val along = cumulative[index]
            if (along > start + EPSILON_METERS && along < end - EPSILON_METERS) {
                result += vertices[index]
            }
        }
        pointAt(end)?.let { point ->
            if (result.isEmpty() || GeoMath.distanceMeters(result.last(), point) > EPSILON_METERS) {
                result += point
            }
        }
        if (result.size == 1) {
            result += result.first()
        }
        return result
    }

    /** One projection onto the line. */
    data class Projection(
        val position: GeoPoint,
        val distanceMeters: Double,
        val alongMeters: Double,
        val remainingMeters: Double,
    )

    private class Segment(
        val start: GeoPoint,
        val end: GeoPoint,
        val startMeters: Double,
        val meters: Double,
        private val latitudeScale: Double,
    ) {
        private val eastMeters: Double
        private val northMeters: Double

        init {
            eastMeters = Math.toRadians(end.longitude - start.longitude) * GeoMath.EARTH_RADIUS_METERS * latitudeScale
            northMeters = Math.toRadians(end.latitude - start.latitude) * GeoMath.EARTH_RADIUS_METERS
        }

        fun project(point: GeoPoint): Projection {
            val pointEast = Math.toRadians(point.longitude - start.longitude) * GeoMath.EARTH_RADIUS_METERS * latitudeScale
            val pointNorth = Math.toRadians(point.latitude - start.latitude) * GeoMath.EARTH_RADIUS_METERS
            val lengthSquared = eastMeters * eastMeters + northMeters * northMeters
            val fraction =
                if (lengthSquared <= 0.0) {
                    0.0
                } else {
                    ((pointEast * eastMeters + pointNorth * northMeters) / lengthSquared).coerceIn(0.0, 1.0)
                }
            val projectedEast = eastMeters * fraction
            val projectedNorth = northMeters * fraction
            val deltaEast = pointEast - projectedEast
            val deltaNorth = pointNorth - projectedNorth
            return Projection(
                position =
                    GeoPoint(
                        latitude = start.latitude + (end.latitude - start.latitude) * fraction,
                        longitude = start.longitude + (end.longitude - start.longitude) * fraction,
                    ),
                distanceMeters = hypot(deltaEast, deltaNorth),
                alongMeters = startMeters + meters * fraction,
                remainingMeters = 0.0,
            )
        }
    }

    private companion object {
        const val MIN_LATITUDE_SCALE = 0.01
        const val EPSILON_METERS = 0.01
    }
}
