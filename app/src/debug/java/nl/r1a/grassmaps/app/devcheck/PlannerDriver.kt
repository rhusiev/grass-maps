package nl.r1a.grassmaps.app.devcheck

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmaps.plugin.api.model.CatalogRegion
import nl.r1a.grassmaps.plugin.api.model.RoutePlanState
import nl.r1a.grassmaps.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmaps.plugins.route.planner.RoutePlannerContract

private const val ROLE_START = "start"
private const val ROLE_DESTINATION = "destination"
private const val DEGREES_PER_HALF_KM_LAT = 0.0045
private const val DEGREES_PER_HALF_KM_LON = 0.0065
private const val BBOX_SIZE = 4
private const val BBOX_WEST = 0
private const val BBOX_SOUTH = 1
private const val BBOX_EAST = 2
private const val BBOX_NORTH = 3

/** The two ends of a planned walk. */
internal data class PlanPoints(
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
)

/** The centre of [region]'s bounding box as latitude and longitude, or null without a 4-value box. */
internal fun regionCentre(region: CatalogRegion): Pair<Double, Double>? {
    val box = region.bbox
    if (box.size != BBOX_SIZE) {
        return null
    }
    return (box[BBOX_SOUTH] + box[BBOX_NORTH]) / 2 to (box[BBOX_WEST] + box[BBOX_EAST]) / 2
}

/** Picks two points about 1 km apart around the centre of [region]'s bounding box, or null without one. */
internal fun pickPlanPoints(region: CatalogRegion): PlanPoints? {
    val box = region.bbox
    val (lat, lon) = regionCentre(region) ?: return null
    val points =
        PlanPoints(
            lat - DEGREES_PER_HALF_KM_LAT,
            lon - DEGREES_PER_HALF_KM_LON,
            lat + DEGREES_PER_HALF_KM_LAT,
            lon + DEGREES_PER_HALF_KM_LON,
        )
    val inside = listOf(points.startLat, points.endLat).all { it in box[BBOX_SOUTH]..box[BBOX_NORTH] }
    return points.takeIf { inside }
}

/** Drives the route planner through its commands, as the control channel does. */
internal class PlannerDriver(
    private val context: CheckContext,
) {
    /** Plans a route between [points] and returns the first finished state, or null on timeout. */
    suspend fun plan(
        points: PlanPoints,
        timeoutMs: Long,
    ): RoutePlanState? {
        context.command("route.planner.begin")
        context.command("route.planner.set_point", pointParams(ROLE_START, points.startLat, points.startLon))
        context.command("route.planner.set_point", pointParams(ROLE_DESTINATION, points.endLat, points.endLon))
        context.command("route.planner.plan")
        return withTimeoutOrNull(timeoutMs) { awaitEnd() }
    }

    suspend fun clear() {
        context.command("route.planner.clear")
    }

    private suspend fun awaitEnd(): RoutePlanState =
        context.container.streamAccess
            .subscribe<RoutePlanState>(RoutePlannerContract.stateStream)
            .first { it.status == RoutePlanStatus.Ready || it.status == RoutePlanStatus.Failed }

    private fun pointParams(
        role: String,
        lat: Double,
        lon: Double,
    ): JsonObject =
        buildJsonObject {
            put("role", role)
            put("lat", lat)
            put("lon", lon)
        }
}
