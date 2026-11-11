package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.service.Strings
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One turn the preview can step through (T-0304): the instruction text, how far
 * along the route it sits, and the point on the line to show for it.
 *
 * The step is a view of the route the planner published, never a second
 * guidance model: the text and the distance come from the engine's own
 * instructions, and the position is read off the same geometry the map draws.
 */
data class RouteTurnStep(
    val index: Int,
    val text: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val position: GeoPoint,
)

/**
 * The turns of the selected route, in the engine's order.
 *
 * A route with instructions has one step per instruction. A route without any
 * instructions - the direct engine's geodesic line is one - has exactly one
 * step: arriving at the destination, at the route's own distance and at the
 * line's last point. "Step" therefore never means an empty control: every drawn
 * route has at least one thing to step to, and a route with maneuvers has one
 * step per maneuver.
 */
fun routePlanTurns(
    state: RoutePlanState,
    strings: Strings,
): List<RouteTurnStep> {
    val option = state.selected ?: return emptyList()
    val geometry = option.geometry
    if (option.instructions.isEmpty()) {
        val end = geometry.lastOrNull() ?: return emptyList()
        return listOf(
            RouteTurnStep(
                index = 0,
                text = strings.resolve(ARRIVE_KEY),
                distanceMeters = option.distanceMeters,
                durationSeconds = option.durationSeconds,
                position = end,
            ),
        )
    }
    return option.instructions.mapIndexed { index, instruction ->
        RouteTurnStep(
            index = index,
            text = instruction.text,
            distanceMeters = instruction.distanceAlongRouteMeters,
            durationSeconds = instruction.durationSeconds,
            position = pointAlong(geometry, instruction.distanceAlongRouteMeters),
        )
    }
}

/**
 * The step at [index], clamped to the list. Null only when there is no route,
 * which is also when the panel draws no stepper.
 */
fun routeTurnStep(
    turns: List<RouteTurnStep>,
    index: Int,
): RouteTurnStep? = if (turns.isEmpty()) null else turns[index.coerceIn(0, turns.lastIndex)]

/** The index [delta] steps from [index], clamped: the first and last turn stop. */
fun routeTurnAdvance(
    turns: List<RouteTurnStep>,
    index: Int,
    delta: Int,
): Int = if (turns.isEmpty()) 0 else (index + delta).coerceIn(0, turns.lastIndex)

/** "at 1.2 km" for one step, using the preview's own distance formatting. */
fun routeTurnDistanceText(
    meters: Double,
    strings: Strings,
    locale: Locale = Locale.getDefault(),
): String = strings.resolve(AT_KEY, listOf(distanceText(meters, strings, locale)))

/** The point [meters] along [geometry], clamped to its two ends. */
internal fun pointAlong(
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
    val a = sin(deltaLat / 2) * sin(deltaLat / 2) + cos(fromLat) * cos(toLat) * sin(deltaLon / 2) * sin(deltaLon / 2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

private const val EARTH_RADIUS_METERS = 6_371_000.0
private const val ARRIVE_KEY = "route_planner_arrive"
private const val AT_KEY = "route_planner_turn_at"
