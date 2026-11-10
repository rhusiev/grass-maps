package nl.r1a.grassmap.routing

import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.provider.Route

/**
 * The route the map draws for a plan. The app passes this to `MapHost.setRoute`
 * and the preview reads the same [RoutePlanState], so the list, the card and
 * the drawn line cannot disagree.
 */
fun RoutePlanState.selectedRoute(): Route? =
    selected?.let { option ->
        Route(
            geometry = option.geometry,
            distanceMeters = option.distanceMeters,
            durationSeconds = option.durationSeconds,
        )
    }
