package nl.r1a.grassmap.plugin.ui.api

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.RouteInstruction
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One instruction of a route, placed by its distance from the route start and
 * by the point on the line to show for it.
 *
 * This is a view of the route the engine published, never a second guidance
 * model: the text and the distance come from the engine's own instructions, and
 * the position is read off the same geometry the map draws. The planner's turn
 * stepper and the guidance panel both walk this one list, so a route's turns
 * are enumerated once.
 */
data class RouteTurnStep(
    val index: Int,
    val text: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val position: GeoPoint,
)

/**
 * The turns of one routed line, in the engine's order.
 *
 * A route with instructions has one step per instruction. A route without any
 * instructions - the direct engine's geodesic line is one - has exactly one
 * step: arriving at the destination, at the route's own distance and at the
 * line's last point. "Step" therefore never means an empty control: every drawn
 * route has at least one thing to step to, and a route with maneuvers has one
 * step per maneuver.
 *
 * The totals are passed separately instead of as a `Route` so the planner's
 * published option and a navigation route reach the same enumeration without
 * one constructing the other.
 */
fun routeTurns(
    geometry: List<GeoPoint>,
    distanceMeters: Double,
    durationSeconds: Double,
    instructions: List<RouteInstruction>,
    arriveText: String,
): List<RouteTurnStep> {
    if (instructions.isEmpty()) {
        val end = geometry.lastOrNull() ?: return emptyList()
        return listOf(
            RouteTurnStep(
                index = 0,
                text = arriveText,
                distanceMeters = distanceMeters,
                durationSeconds = durationSeconds,
                position = end,
            ),
        )
    }
    return instructions.mapIndexed { index, instruction ->
        RouteTurnStep(
            index = index,
            text = instruction.text,
            distanceMeters = instruction.distanceAlongRouteMeters,
            durationSeconds = instruction.durationSeconds,
            position = pointAlong(geometry, instruction.distanceAlongRouteMeters),
        )
    }
}

/** The point [meters] along [geometry], clamped to its two ends. */
private fun pointAlong(
    geometry: List<GeoPoint>,
    meters: Double,
): GeoPoint {
    if (geometry.isEmpty()) {
        return GeoPoint(0.0, 0.0)
    }
    if (meters <= 0.0 || geometry.size == 1) {
        return geometry.first()
    }
    var remaining = meters
    for (index in 1 until geometry.size) {
        val from = geometry[index - 1]
        val to = geometry[index]
        val length = distanceMeters(from, to)
        if (length <= 0.0) {
            continue
        }
        if (remaining <= length) {
            return interpolate(from, to, remaining / length)
        }
        remaining -= length
    }
    return geometry.last()
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

/** The great-circle distance between two points, in metres. */
private fun distanceMeters(
    from: GeoPoint,
    to: GeoPoint,
): Double {
    val fromLat = Math.toRadians(from.latitude)
    val toLat = Math.toRadians(to.latitude)
    val deltaLat = toLat - fromLat
    val deltaLon = Math.toRadians(to.longitude - from.longitude)
    val a = sin(deltaLat / 2) * sin(deltaLat / 2) + cos(fromLat) * cos(toLat) * sin(deltaLon / 2) * sin(deltaLon / 2) * sin(deltaLon / 2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

private const val EARTH_RADIUS_METERS = 6_371_000.0
