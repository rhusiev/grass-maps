package nl.r1a.grassmap.plugin.api.host

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.RouteRelations
import nl.r1a.grassmap.plugin.api.model.WayAlertHit
import nl.r1a.grassmap.plugin.api.provider.Route

/**
 * The mounted region's way-alert features and hiking route relations.
 *
 * The host reads the region package; a caller asks for what it needs. The
 * two components are independent, so a package may carry either one.
 *
 * This is region data, not Valhalla tiles: a route engine's response does not
 * carry hazards, fords or barriers, and it does not carry relation membership.
 * A region without a component is a success with an empty value, because older
 * packages are valid; unreadable component data is a failure.
 */
interface RegionAccess {
    /**
     * A feature is returned when any part of it lies within [corridorMeters] of
     * [route], with the earliest along-route distance where the route meets the
     * corridor.
     */
    suspend fun wayAlertFeatures(
        route: Route,
        corridorMeters: Double,
    ): Outcome<List<WayAlertHit>>

    /**
     * The mounted region's hiking route relations and their member-way
     * geometry (T-0418). A package without the component is
     * [Outcome.Success] with [RouteRelations.EMPTY].
     */
    suspend fun routeRelations(): Outcome<RouteRelations> = Outcome.Success(RouteRelations.EMPTY)
}

/** The source a host uses before a region with either component is mounted. */
object NoRegionAccess : RegionAccess {
    override suspend fun wayAlertFeatures(
        route: Route,
        corridorMeters: Double,
    ): Outcome<List<WayAlertHit>> = Outcome.Success(emptyList())
}
