package nl.r1a.grassmap.plugin.api.provider

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The route's own time model, counted down from the position's distance along
 * the route.
 *
 * It lives in `plugin-api` because two surfaces count from it: the navigation
 * session publishes remaining time and the guidance panel times the next turn
 * and the next stop. One model keeps those numbers from drifting apart.
 *
 * The model is built from the route itself, never from a second pass over the
 * whole-route totals: guidance must not average a slow leg and a fast leg into
 * one speed. Spans are taken from the finest consistent source the engine gave:
 * the per-maneuver times of [Route.instructions] (each maneuver carries its own
 * duration), then the per-leg times of [Route.segments], and only a route with
 * neither falls back to one uniform span.
 *
 * A source is consistent when its distances end at the route's total distance
 * and its durations add up to the route's total duration. An inconsistent
 * source is dropped rather than corrected silently, because a "corrected"
 * model would be a second calculation of exactly the number this class exists
 * to keep honest.
 */
class RouteProgress private constructor(
    val totalDistanceMeters: Double,
    private val spans: List<Span>,
    /** The source the spans came from, for the session log. */
    val source: Source,
) {
    /** The distance still to travel from [alongMeters]. */
    fun remainingMeters(alongMeters: Double): Double = totalDistanceMeters - alongMeters.coerceIn(0.0, totalDistanceMeters)

    /** The time still to travel from [alongMeters], span by span. */
    fun remainingSeconds(alongMeters: Double): Double {
        val along = alongMeters.coerceIn(0.0, totalDistanceMeters)
        return spans.sumOf { span ->
            val travelled =
                when {
                    span.endMeters <= along -> 1.0
                    span.startMeters >= along -> 0.0
                    else -> (along - span.startMeters) / (span.endMeters - span.startMeters)
                }
            span.durationSeconds * (1.0 - travelled)
        }
    }

    /**
     * The time spent travelling from [fromMeters] to [toMeters], never negative
     * when the two are in order. Both ends are clamped to the route.
     */
    fun durationBetween(
        fromMeters: Double,
        toMeters: Double,
    ): Double = (remainingSeconds(fromMeters) - remainingSeconds(toMeters)).coerceAtLeast(0.0)

    /** How many spans the model has, for the session log. */
    val spanCount: Int get() = spans.size

    private data class Span(
        val startMeters: Double,
        val endMeters: Double,
        val durationSeconds: Double,
    )

    /** Where [spans] came from; [Uniform] means the route carried no model. */
    enum class Source {
        Instructions,
        Legs,
        Uniform,
    }

    companion object {
        private const val DISTANCE_RELATIVE_TOLERANCE = 0.005
        private const val DURATION_RELATIVE_TOLERANCE = 0.005
        private const val MIN_TOLERANCE_METERS = 1.0
        private const val MIN_TOLERANCE_SECONDS = 1.0

        fun of(route: Route): RouteProgress {
            val total =
                if (route.distanceMeters.isFinite() && route.distanceMeters > 0.0) {
                    route.distanceMeters
                } else {
                    lineLengthMeters(route.geometry)
                }
            if (total <= 0.0) {
                return RouteProgress(totalDistanceMeters = 0.0, spans = emptyList(), source = Source.Uniform)
            }
            val fromInstructions = spansOfInstructions(route, total)
            if (fromInstructions != null) {
                return RouteProgress(total, fromInstructions, Source.Instructions)
            }
            val fromLegs = spansOfLegs(route, total)
            if (fromLegs != null) {
                return RouteProgress(total, fromLegs, Source.Legs)
            }
            return RouteProgress(
                totalDistanceMeters = total,
                spans = listOf(Span(0.0, total, route.durationSeconds.coerceAtLeast(0.0))),
                source = Source.Uniform,
            )
        }

        private fun spansOfInstructions(
            route: Route,
            total: Double,
        ): List<Span>? {
            if (route.instructions.size < MIN_SPANS || !route.durationSeconds.isConsistentTotal()) {
                return null
            }
            val starts =
                route.instructions
                    .sortedBy { it.distanceAlongRouteMeters }
                    .map { it.distanceAlongRouteMeters }
            if (starts.first() > MIN_TOLERANCE_METERS || starts.last() > total + MIN_TOLERANCE_METERS) {
                return null
            }
            val durations = route.instructions.sortedBy { it.distanceAlongRouteMeters }.map { it.durationSeconds }
            if (durations.any { !it.isFinite() || it < 0.0 }) {
                return null
            }
            if (!durations.sum().isCloseTo(route.durationSeconds, MIN_TOLERANCE_SECONDS, DURATION_RELATIVE_TOLERANCE)) {
                return null
            }
            return starts.indices.map { index ->
                Span(
                    startMeters = starts[index],
                    endMeters = if (index + 1 < starts.size) starts[index + 1] else total,
                    durationSeconds = durations[index],
                )
            }
        }

        private fun spansOfLegs(
            route: Route,
            total: Double,
        ): List<Span>? {
            if (route.segments.size < MIN_SPANS || !route.durationSeconds.isConsistentTotal()) {
                return null
            }
            val distances = route.segments.map { it.distanceMeters }
            val durations = route.segments.map { it.durationSeconds }
            if (distances.any { !it.isFinite() || it < 0.0 } || durations.any { !it.isFinite() || it < 0.0 }) {
                return null
            }
            if (!distances.sum().isCloseTo(total, MIN_TOLERANCE_METERS, DISTANCE_RELATIVE_TOLERANCE)) {
                return null
            }
            if (!durations.sum().isCloseTo(route.durationSeconds, MIN_TOLERANCE_SECONDS, DURATION_RELATIVE_TOLERANCE)) {
                return null
            }
            var start = 0.0
            return distances.mapIndexed { index, distance ->
                Span(startMeters = start, endMeters = start + distance, durationSeconds = durations[index])
                    .also { start += distance }
            }
        }

        private fun Double.isConsistentTotal(): Boolean = isFinite() && this > 0.0

        private fun Double.isCloseTo(
            expected: Double,
            minimumTolerance: Double,
            relativeTolerance: Double,
        ): Boolean = abs(this - expected) <= maxOf(minimumTolerance, relativeTolerance * abs(expected))

        private const val MIN_SPANS = 2
        private const val EARTH_RADIUS_METERS = 6_371_000.0

        /**
         * The line's own length, for a route that carries no usable total. It
         * uses the same radius the follower's locator does, so the fallback
         * total and an engine's distance agree to the metre.
         */
        private fun lineLengthMeters(points: List<GeoPoint>): Double {
            var total = 0.0
            for (index in 1 until points.size) {
                val from = points[index - 1]
                val to = points[index]
                val fromLat = Math.toRadians(from.latitude)
                val toLat = Math.toRadians(to.latitude)
                val deltaLat = toLat - fromLat
                val deltaLon = Math.toRadians(to.longitude - from.longitude)
                val a = sin(deltaLat / 2) * sin(deltaLat / 2) + cos(fromLat) * cos(toLat) * sin(deltaLon / 2) * sin(deltaLon / 2)
                total += 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
            }
            return total
        }
    }
}
