package nl.r1a.grassmap.plugin.api.host

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.WayAlertHit
import nl.r1a.grassmap.plugin.api.provider.Route

/**
 * The mounted region's way-alert features, queried along a route (T-0319).
 *
 * The host reads the region package; the caller passes the active route and
 * the corridor width. A feature is returned when any part of it lies within
 * that many metres of the route line, with the earliest along-route distance
 * where the route meets the corridor.
 *
 * This is region data, not Valhalla tiles: a route engine's response does not
 * carry hazards, fords or barriers, and T-0301 verified that its tiles do not.
 * A region without the way-alert component is a success with an empty list,
 * because older packages are valid; unreadable component data is a failure.
 */
fun interface RegionAccess {
    suspend fun wayAlertFeatures(
        route: Route,
        corridorMeters: Double,
    ): Outcome<List<WayAlertHit>>
}

/** The source a host uses before a region with the way-alert component is mounted. */
object NoRegionAccess : RegionAccess {
    override suspend fun wayAlertFeatures(
        route: Route,
        corridorMeters: Double,
    ): Outcome<List<WayAlertHit>> = Outcome.Success(emptyList())
}
