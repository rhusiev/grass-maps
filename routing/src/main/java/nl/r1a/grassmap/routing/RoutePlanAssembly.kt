package nl.r1a.grassmap.routing

import nl.r1a.grassmap.plugin.api.model.RoutePlanOption
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.provider.Route

/**
 * The route the map draws for a plan. The app passes this to `MapHost.setRoute`
 * and the preview reads the same [RoutePlanState], so the list, the card and
 * the drawn line cannot disagree.
 */
fun RoutePlanState.selectedRoute(): Route? = selected?.toRoute()

/**
 * One published option as the engine's route. The annotate pass asks the
 * engine for the same route the option already holds, so this is also the
 * input that pass starts from (T-0409).
 */
fun RoutePlanOption.toRoute(): Route =
    Route(
        geometry = geometry,
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        segments = segments,
        instructions = instructions,
    )
