package nl.r1a.grassmap.navigation

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.provider.Route
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The seeded replay harness shared by the desktop test and the instrumented
 * Ferrostar test (T-0309).
 *
 * The geometry and distance code here is deliberately independent of
 * [PolylineLocator] and [GeoMath]: the harness measures the follower, so a
 * measurement built on the follower's own arithmetic would only prove that the
 * code agrees with itself. The haversine radius here is the IUGG mean radius,
 * not the locator's rounded 6 371 km.
 */
internal object ReplayHarness {
    /** How far every replayed fix is moved from the track point, in metres. */
    const val NOISE_METERS: Double = 15.0

    /** The seed that makes the noise reproducible across JVM and Android runs. */
    const val NOISE_SEED: Long = 20_261_110L

    /** The worst snapped-to-route distance the acceptance allows, in metres. */
    const val SNAP_BOUND_METERS: Double = 2.0

    /** GPX fixes report no horizontal accuracy; the app's mock source uses 5 m. */
    const val FIX_ACCURACY_METERS: Double = 5.0

    /** One track point from the committed GPX fixture. */
    data class TrackPoint(
        val position: GeoPoint,
        val timeMillis: Long,
    )

    /** Reads the `trkpt` points of a GPX 1.0/1.1 document, in document order. */
    fun parseGpx(gpx: String): List<TrackPoint> {
        val points = mutableListOf<TrackPoint>()
        val pointPattern = Regex("<trkpt\\b([^>]*)>(.*?)</trkpt>", RegexOption.DOT_MATCHES_ALL)
        val timePattern = Regex("<time>([^<]+)</time>")
        for (match in pointPattern.findAll(gpx)) {
            val attributes = match.groupValues[1]
            val body = match.groupValues[2]
            val latitude = attribute(attributes, "lat")?.toDoubleOrNull() ?: continue
            val longitude = attribute(attributes, "lon")?.toDoubleOrNull() ?: continue
            val time = timePattern.find(body)?.groupValues?.get(1)
            points +=
                TrackPoint(
                    position = GeoPoint(latitude = latitude, longitude = longitude),
                    timeMillis = time?.let(::parseEpochMillis) ?: points.size * DEFAULT_TICK_MILLIS,
                )
        }
        return points
    }

    /**
     * Copies [points] into fixes, each moved [noiseMeters] from its track point
     * in a seeded random direction. The same seed gives the same replay on the
     * JVM and on the device.
     */
    fun noisyFixes(
        points: List<TrackPoint>,
        noiseMeters: Double = NOISE_METERS,
        seed: Long = NOISE_SEED,
    ): List<LocationFix> {
        val random = java.util.Random(seed)
        return points.map { point ->
            val bearing = random.nextDouble() * FULL_CIRCLE_DEGREES
            LocationFix(
                position = offset(point.position, bearing, noiseMeters),
                accuracyMeters = FIX_ACCURACY_METERS,
                timeMillis = point.timeMillis,
            )
        }
    }

    /**
     * The route a replay follows: the track line with a uniform walking model,
     * continued straight for [tailMeters] past the last point.
     *
     * The fixture is an 82 m loop, so its own end sits within one noise radius
     * of its start. The tail keeps the destination out of reach of the replay,
     * so the run measures every fix instead of ending on a noisy snap near the
     * finish; the measured line still contains the committed track unchanged.
     */
    fun routeWithTail(
        points: List<TrackPoint>,
        tailMeters: Double = ROUTE_TAIL_METERS,
        speedMetersPerSecond: Double = WALKING_SPEED_METERS_PER_SECOND,
    ): Route {
        val geometry = points.map { it.position }.toMutableList()
        if (geometry.size >= 2 && tailMeters > 0.0) {
            val bearing = bearingDegrees(geometry[geometry.size - 2], geometry.last())
            geometry += offset(geometry.last(), bearing, tailMeters)
        }
        val distance = lineLengthMeters(geometry)
        return Route(
            geometry = geometry,
            distanceMeters = distance,
            durationSeconds = distance / speedMetersPerSecond,
        )
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
        return (Math.toDegrees(atan2(y, x)) + FULL_CIRCLE_DEGREES) % FULL_CIRCLE_DEGREES
    }

    /** The worst distance from any snapped point to the geometry line, in metres. */
    fun worstDeviationMeters(
        geometry: List<GeoPoint>,
        snapped: List<GeoPoint>,
    ): Double = snapped.maxOfOrNull { distanceToLineMeters(it, geometry) } ?: 0.0

    /** The shortest distance from [point] to any segment of [geometry], in metres. */
    fun distanceToLineMeters(
        point: GeoPoint,
        geometry: List<GeoPoint>,
    ): Double {
        if (geometry.isEmpty()) {
            return Double.POSITIVE_INFINITY
        }
        if (geometry.size == 1) {
            return haversineMeters(point, geometry.first())
        }
        return (1 until geometry.size).minOf { index ->
            distanceToSegmentMeters(point, geometry[index - 1], geometry[index])
        }
    }

    /** The great-circle length of a line, measured segment by segment. */
    fun lineLengthMeters(geometry: List<GeoPoint>): Double =
        (1 until geometry.size).sumOf { index -> haversineMeters(geometry[index - 1], geometry[index]) }

    /** The great-circle distance between two points, in metres. */
    fun haversineMeters(
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

    private fun distanceToSegmentMeters(
        point: GeoPoint,
        start: GeoPoint,
        end: GeoPoint,
    ): Double {
        val latitudeScale = cos(Math.toRadians(point.latitude)).coerceAtLeast(MIN_LATITUDE_SCALE)
        val endEast = Math.toRadians(end.longitude - start.longitude) * EARTH_RADIUS_METERS * latitudeScale
        val endNorth = Math.toRadians(end.latitude - start.latitude) * EARTH_RADIUS_METERS
        val pointEast = Math.toRadians(point.longitude - start.longitude) * EARTH_RADIUS_METERS * latitudeScale
        val pointNorth = Math.toRadians(point.latitude - start.latitude) * EARTH_RADIUS_METERS
        val lengthSquared = endEast * endEast + endNorth * endNorth
        val fraction =
            if (lengthSquared <= 0.0) {
                0.0
            } else {
                ((pointEast * endEast + pointNorth * endNorth) / lengthSquared).coerceIn(0.0, 1.0)
            }
        val deltaEast = pointEast - endEast * fraction
        val deltaNorth = pointNorth - endNorth * fraction
        return hypot(deltaEast, deltaNorth)
    }

    private fun offset(
        origin: GeoPoint,
        bearingDegrees: Double,
        distanceMeters: Double,
    ): GeoPoint {
        val angular = distanceMeters / EARTH_RADIUS_METERS
        val bearing = Math.toRadians(bearingDegrees)
        val latitude = Math.toRadians(origin.latitude)
        val longitude = Math.toRadians(origin.longitude)
        val targetLat = asin(sin(latitude) * cos(angular) + cos(latitude) * sin(angular) * cos(bearing))
        val targetLon =
            longitude +
                atan2(
                    sin(bearing) * sin(angular) * cos(latitude),
                    cos(angular) - sin(latitude) * sin(targetLat),
                )
        val wrapped = ((Math.toDegrees(targetLon) + HALF_CIRCLE_DEGREES * 3.0) % FULL_CIRCLE_DEGREES) - HALF_CIRCLE_DEGREES
        return GeoPoint(latitude = Math.toDegrees(targetLat), longitude = wrapped)
    }

    private fun attribute(
        attributes: String,
        name: String,
    ): String? = Regex("\\b" + name + "\\s*=\\s*\"([^\"]*)\"").find(attributes)?.groupValues?.get(1)

    private fun parseEpochMillis(text: String): Long =
        java.time.Instant
            .parse(text.trim())
            .toEpochMilli()

    private const val EARTH_RADIUS_METERS = 6_371_008.8
    private const val MIN_LATITUDE_SCALE = 0.01
    private const val FULL_CIRCLE_DEGREES = 360.0
    private const val HALF_CIRCLE_DEGREES = 180.0
    private const val DEFAULT_TICK_MILLIS = 1_000L
    private const val WALKING_SPEED_METERS_PER_SECOND = 1.4
    private const val ROUTE_TAIL_METERS = 300.0
}
