package nl.r1a.grassmap.navigation

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.provider.Route

/**
 * The route follower that needs no native core: it projects each fix onto the
 * route line with [PolylineLocator] and reports the projection.
 *
 * It is the session's fallback when the primary engine cannot start, and it is
 * a real [RouteFollowerEngine], so the desktop gate can exercise the whole
 * session on a replay. It deliberately reports no deviation verdict
 * ([FollowerDeviation.Unknown]): the off-route decision stays with the session,
 * which applies one threshold to every engine.
 *
 * Remaining distance is reported in the route's own distance space, not the
 * geometry's, so the session can subtract it from `Route.distanceMeters` even
 * when the engine rounded the total.
 */
class GeometryRouteFollower(
    private val config: NavigationConfig = NavigationConfig(),
    private val log: (String) -> Unit = {},
) : RouteFollowerEngine {
    override fun begin(route: Route): Outcome<RouteFollowerHandle> {
        if (route.geometry.size < MIN_ROUTE_POINTS) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        return try {
            val locator = PolylineLocator(route.geometry)
            val total =
                if (route.distanceMeters.isFinite() && route.distanceMeters > 0.0) {
                    route.distanceMeters
                } else {
                    locator.totalMeters()
                }
            Outcome.Success(Handle(locator, total, config.arrivalDistanceMeters))
        } catch (error: Exception) {
            log("geometry-follower begin failed: ${error.javaClass.simpleName}: ${error.message}")
            Outcome.Failure(AppError.Unsupported)
        }
    }

    private class Handle(
        private val locator: PolylineLocator,
        private val totalMeters: Double,
        private val arrivalDistanceMeters: Double,
    ) : RouteFollowerHandle {
        @Synchronized
        override fun initialFix(fix: LocationFix): Outcome<FollowerSnapshot> = read(fix)

        @Synchronized
        override fun update(fix: LocationFix): Outcome<FollowerSnapshot> = read(fix)

        override fun close() {
            // Nothing native to release.
        }

        private fun read(fix: LocationFix): Outcome<FollowerSnapshot> {
            val projection = locator.locate(fix.position) ?: return Outcome.Failure(AppError.InvalidInput)
            val lineMeters = locator.totalMeters()
            val along =
                if (lineMeters > 0.0) {
                    totalMeters * (projection.alongMeters / lineMeters).coerceIn(0.0, 1.0)
                } else {
                    0.0
                }
            val remaining = (totalMeters - along).coerceAtLeast(0.0)
            return Outcome.Success(
                FollowerSnapshot(
                    snappedPosition = projection.position,
                    deviation = FollowerDeviation.Unknown,
                    deviationMeters = projection.distanceMeters,
                    arrived = remaining <= arrivalDistanceMeters,
                    remainingDistanceMeters = remaining,
                ),
            )
        }
    }

    private companion object {
        const val MIN_ROUTE_POINTS = 2
    }
}
