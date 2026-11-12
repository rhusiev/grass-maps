package nl.r1a.grassmap.map.style

import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import nl.r1a.grassmap.map.MapDistance
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteInstruction
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** One turn arrow: its point on the route and its clockwise bearing from north. */
internal data class RouteArrow(
    val position: GeoPoint,
    val bearingDegrees: Double,
)

/**
 * The turn arrows of this route revision (T-0311).
 *
 * One arrow is produced for every instruction whose Valhalla maneuver changes
 * direction: turns, ramps and exits, forks, merges and roundabouts. Start and
 * arrival maneuvers are skipped, as are continue, keep-straight, ferry and
 * transit instructions that do not bend the line. A via is structural, not a
 * maneuver: its leg adds instructions, and a destination instruction at the
 * via is skipped by the same arrival rule. A reroute publishes a new [Route],
 * so it produces a new list and the old arrows disappear with the old line.
 *
 * The position is read along the geometry, not from the engine's totals, and
 * the bearing is the direction of the segment that starts there. A turn is
 * therefore shown pointing the way the route leaves the corner.
 */
internal fun Route.turnArrows(): List<RouteArrow> {
    if (geometry.size < MIN_GEOMETRY_POINTS) {
        return emptyList()
    }
    val line = RouteLine.of(geometry) ?: return emptyList()
    return instructions
        .asSequence()
        .filter { it.isTurnManeuver() }
        .filter { it.distanceAlongRouteMeters.isFinite() }
        .sortedBy { it.distanceAlongRouteMeters }
        .mapNotNull { instruction ->
            val distance = instruction.distanceAlongRouteMeters.coerceIn(0.0, line.lengthMeters)
            line.pointAt(distance)?.let { point ->
                RouteArrow(position = point, bearingDegrees = line.bearingAt(distance))
            }
        }.toList()
}

/** The arrows as one GeoJSON FeatureCollection, one Point feature each. */
internal fun List<RouteArrow>.toArrowFeatureCollection(): String =
    buildJsonObject {
        put("type", "FeatureCollection")
        putJsonArray("features") {
            for (arrow in this@toArrowFeatureCollection) {
                addJsonObject {
                    put("type", "Feature")
                    putJsonObject("properties") {
                        put(BEARING_PROPERTY, arrow.bearingDegrees)
                    }
                    putJsonObject("geometry") {
                        put("type", "Point")
                        putJsonArray("coordinates") {
                            add(arrow.position.longitude)
                            add(arrow.position.latitude)
                        }
                    }
                }
            }
        }
    }.toString()

/** The property the symbol layer reads for `icon-rotate`. */
internal const val BEARING_PROPERTY: String = "bearing"

/** True for the Valhalla maneuver codes that bend the route. */
internal fun RouteInstruction.isTurnManeuver(): Boolean {
    val code = maneuver?.toIntOrNull() ?: return false
    return code in TURN_MANEUVER_CODES
}

// Valhalla codes: 9-16 turns, 17-21 ramps and exits, 23-24 forks, 25 merge,
// 26-27 roundabout. Start (1-3), destination (4-6), continue (0, 7, 8, 22),
// ferry (28-29) and transit (30-39) do not get an arrow.
private val TURN_MANEUVER_CODES: Set<Int> = (9..21).toSet() + (23..27).toSet()

private const val MIN_GEOMETRY_POINTS = 2

/**
 * The route geometry as a distance scale, built once per route revision. A zero
 * length segment adds no distance, so a repeated point never divides by zero;
 * the segment chosen at a vertex is its outgoing one.
 */
private class RouteLine private constructor(
    private val points: List<GeoPoint>,
    private val cumulativeMeters: DoubleArray,
    val lengthMeters: Double,
) {
    fun pointAt(meters: Double): GeoPoint? {
        if (points.isEmpty()) {
            return null
        }
        if (meters <= 0.0 || points.size == 1) {
            return points.first()
        }
        val index = segmentIndex(meters)
        val startMeters = cumulativeMeters[index]
        val segmentMeters = cumulativeMeters[index + 1] - startMeters
        if (segmentMeters <= 0.0) {
            return points[index]
        }
        val fraction = ((meters - startMeters) / segmentMeters).coerceIn(0.0, 1.0)
        return interpolate(points[index], points[index + 1], fraction)
    }

    fun bearingAt(meters: Double): Double {
        val index = segmentIndex(meters)
        return bearing(points[index], points[index + 1])
    }

    /** The segment that contains [meters]; a vertex belongs to its outgoing one. */
    private fun segmentIndex(meters: Double): Int {
        val clamped = meters.coerceIn(0.0, lengthMeters)
        var low = 0
        var high = cumulativeMeters.lastIndex - 1
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (cumulativeMeters[middle] <= clamped) {
                low = middle
            } else {
                high = middle - 1
            }
        }
        return low
    }

    companion object {
        fun of(points: List<GeoPoint>): RouteLine? {
            if (points.size < MIN_GEOMETRY_POINTS) {
                return null
            }
            val cumulative = DoubleArray(points.size)
            for (index in 1 until points.size) {
                cumulative[index] =
                    cumulative[index - 1] + MapDistance.meters(points[index - 1], points[index])
            }
            val length = cumulative.last()
            if (length <= 0.0) {
                return null
            }
            return RouteLine(points, cumulative, length)
        }
    }
}

private fun interpolate(
    from: GeoPoint,
    to: GeoPoint,
    fraction: Double,
): GeoPoint =
    GeoPoint(
        latitude = from.latitude + (to.latitude - from.latitude) * fraction,
        longitude = from.longitude + (to.longitude - from.longitude) * fraction,
    )

/** The initial great-circle bearing from [from] to [to], clockwise from north. */
private fun bearing(
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

private const val FULL_CIRCLE_DEGREES = 360.0
