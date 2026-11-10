package nl.r1a.grassmap.plugins.direct

import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.ProviderContract
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteModifierRules
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.provider.RouteSegment
import nl.r1a.grassmap.plugin.api.provider.RoutingCapabilities
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine
import nl.r1a.grassmap.plugin.api.provider.RoutingEngineContract
import kotlin.math.abs
import kotlin.math.ceil

/**
 * The second `routing.engine` provider: a straight line from each waypoint to
 * the next, with no packed data, no native code, no network and no map.
 *
 * The route between two points is the geodesic. Its length is the haversine
 * distance of the pair, not the sum of the sampled line, and the line holds
 * enough vertices to draw that arc without cutting the corner. A chain of
 * waypoints becomes one route with one leg per hop, in visit order; the route
 * is the sum of the legs, and each leg keeps its own segment so a consumer can
 * tell which hop a stretch belongs to.
 *
 * This engine is the proof that the engine seam is real: the route planner and
 * the follower consume it exactly as they consume Valhalla, and the only thing
 * that changes is that every answer comes from geometry instead of a graph.
 */
class DirectRoutingEngine : RoutingEngine {
    override val contract: ProviderContract = RoutingEngineContract.REFERENCE

    /**
     * Honest answers for an engine with no map data: the three travel modes it
     * can answer, one route (a geodesic has no alternative), no cost overlays,
     * no per-segment attributes and no track matching. The empty sets are
     * declarations, not placeholders: the host refuses a request this engine
     * cannot translate instead of the engine half answering it.
     */
    override fun capabilities(): RoutingCapabilities =
        RoutingCapabilities(
            profiles = PROFILE_SPEEDS.keys,
            modifierKinds = emptySet(),
            maxAlternatives = 0,
            maxDistanceMeters = MAX_DISTANCE_METRES,
            attributeKeys = emptySet(),
            hasMatchTrack = false,
        )

    /**
     * Routes through [RouteRequest.waypoints] in order.
     *
     * The work is a few hundred floating-point operations for the largest
     * accepted route, so it runs on the calling coroutine rather than taking a
     * dispatcher; there is no I/O to move off Main.
     */
    override suspend fun route(
        request: RouteRequest,
        cancellation: CancellationHandle,
    ): Outcome<List<Route>> {
        val legs = legLengths(request.waypoints)
        invalidReason(request, legs)?.let { return Outcome.Failure(it) }
        if (cancellation.isCancelled()) {
            return Outcome.Failure(AppError.Cancelled)
        }
        return Outcome.Success(listOf(routeOf(request, legs)))
    }

    /**
     * A no-op by capability, not by omission: this engine declares no attribute
     * keys and has no graph to match the line against, so there is nothing to
     * add and the route comes back exactly as it went in. The contract's answer
     * for an engine without the capability is the unchanged route, which is why
     * this is a success rather than `Unsupported`.
     */
    override suspend fun annotate(
        route: Route,
        profile: RouteProfile,
        cancellation: CancellationHandle,
    ): Outcome<Route> {
        if (cancellation.isCancelled()) {
            return Outcome.Failure(AppError.Cancelled)
        }
        return Outcome.Success(route)
    }

    /**
     * One route over the whole waypoint chain: a shared vertex at each waypoint,
     * one [RouteSegment] per leg, and the leg distances added into the total.
     *
     * Segment indices are half-open and neighbouring legs share the stop vertex,
     * so `geometry.subList(startIndex, endIndex)` is the leg from its start to
     * its end inclusive. The route carries no instructions: a geodesic has no
     * maneuvers, no street names and no turns, and inventing them would put
     * guidance the engine cannot justify in front of the driver.
     */
    private fun routeOf(
        request: RouteRequest,
        legs: List<Double>,
    ): Route {
        val speed = PROFILE_SPEEDS.getValue(request.profile)
        val geometry = mutableListOf(request.waypoints.first())
        val segments = mutableListOf<RouteSegment>()
        var distance = 0.0
        var duration = 0.0
        for ((index, legMetres) in legs.withIndex()) {
            val legSeconds = legMetres / speed
            val startIndex = geometry.size - 1
            val points =
                Geodesic.polyline(
                    from = request.waypoints[index],
                    to = request.waypoints[index + 1],
                    segments = segmentsFor(legMetres),
                )
            geometry += points.drop(1)
            segments +=
                RouteSegment(
                    startIndex = startIndex,
                    endIndex = geometry.size,
                    distanceMeters = legMetres,
                    durationSeconds = legSeconds,
                )
            distance += legMetres
            duration += legSeconds
        }
        return Route(
            geometry = geometry,
            distanceMeters = distance,
            durationSeconds = duration,
            segments = segments,
        )
    }

    /**
     * One vertex per kilometre of leg, at least one arc.
     *
     * One kilometre is the spacing where the sampled line stops being the
     * question: the sagitta of a 1 km arc is about 2 cm, far below what the map
     * draws and what a fix's accuracy can resolve, and the longest accepted
     * route is then 250 segments - small enough to send to the map and to snap
     * against without turning a straight line into a payload. A shorter leg
     * gets fewer points, down to the two its ends need.
     */
    private fun segmentsFor(legMetres: Double): Int = ceil(legMetres / MAX_VERTEX_SPACING_METRES).toInt().coerceAtLeast(1)

    private fun legLengths(waypoints: List<GeoPoint>): List<Double> =
        waypoints.zipWithNext { from, to -> Geodesic.distanceMetres(from, to) }

    /**
     * The request checks the host would otherwise have to repeat: one route is
     * the only answer, so an alternative or a cost overlay is outside what this
     * engine declares. A leg below [MIN_LEG_METRES] has no line a follower can
     * walk, and a chain beyond [MAX_DISTANCE_METRES] is refused rather than
     * answered with a line nobody asked to draw.
     */
    private fun invalidReason(
        request: RouteRequest,
        legs: List<Double>,
    ): AppError? {
        val capabilities = capabilities()
        if (request.waypoints.size < MIN_WAYPOINTS) {
            return AppError.InvalidInput
        }
        if (request.profile !in capabilities.profiles) {
            return AppError.Unsupported
        }
        if (request.alternatives !in 0..capabilities.maxAlternatives) {
            return AppError.InvalidInput
        }
        for (modifier in request.modifiers) {
            RouteModifierRules.violationOf(modifier)?.let { return it }
            if (modifier.kind !in capabilities.modifierKinds) {
                return AppError.Unsupported
            }
        }
        if (legs.any { !it.isFinite() } || legs.any { it < MIN_LEG_METRES }) {
            return AppError.InvalidInput
        }
        if (request.waypoints.zipWithNext().any { (from, to) -> crossesTheAntimeridian(from, to) }) {
            return AppError.Unsupported
        }
        if (legs.sum() > capabilities.maxDistanceMeters) {
            return AppError.InvalidInput
        }
        return null
    }

    /**
     * A leg across the antimeridian comes back with a longitude jump. The map
     * would draw it the long way round and the follower's local-plane snap
     * would measure it as most of the planet, so the engine refuses the leg
     * rather than answering with a line that is wrong. No region in this
     * project crosses the seam.
     */
    private fun crossesTheAntimeridian(
        from: GeoPoint,
        to: GeoPoint,
    ): Boolean = abs(to.longitude - from.longitude) > HALF_TURN_DEGREES

    private companion object {
        const val MIN_WAYPOINTS = 2
        const val MIN_LEG_METRES = 1.0
        const val MAX_DISTANCE_METRES = 250_000.0
        const val MAX_VERTEX_SPACING_METRES = 1_000.0
        const val HALF_TURN_DEGREES = 180.0

        /**
         * Cruising speeds picked for a line with no surface information, not
         * derived from a measurement: 1.4 m/s walking, 1.1 m/s hiking and
         * 4.2 m/s cycling. A direct route cannot know the ground, so these are
         * the only honest numbers it can offer; an engine with map data owns a
         * real estimate. Car is not declared: the crow does not drive.
         */
        val PROFILE_SPEEDS: Map<RouteProfile, Double> =
            mapOf(
                RouteProfile.Walk to 1.4,
                RouteProfile.Bike to 4.2,
                RouteProfile.Hike to 1.1,
            )
    }
}
