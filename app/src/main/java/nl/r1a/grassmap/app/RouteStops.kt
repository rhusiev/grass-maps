package nl.r1a.grassmap.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.places.Favourite
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmap.plugin.api.provider.PlaceHit

/**
 * The two sources a stop can come from (T-0303): a stored favourite and a
 * search result. Both keep the name the user picked, because the stop list
 * labels a via by it, and the coordinate is the source's own rather than
 * something re-read from the label text.
 */
internal fun Favourite.toRouteStop(): RoutePlanPoint =
    RoutePlanPoint(
        position = GeoPoint(latitude = latitude, longitude = longitude),
        label = name,
    )

internal fun PlaceHit.toRouteStop(): RoutePlanPoint =
    RoutePlanPoint(
        position = position,
        label = name,
    )

/** The `route.planner.add_via` parameters of one stop. */
internal fun routeStopParams(stop: RoutePlanPoint): JsonObject =
    buildJsonObject {
        put("lat", stop.position.latitude)
        put("lon", stop.position.longitude)
        if (stop.label.isNotEmpty()) {
            put("label", stop.label)
        }
    }
