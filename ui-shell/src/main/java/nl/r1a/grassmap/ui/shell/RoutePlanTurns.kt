package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.ui.api.RouteTurnStep
import nl.r1a.grassmap.plugin.ui.api.routeTurns
import java.util.Locale

/**
 * The planner's step-through of the selected route's instructions (T-0304).
 *
 * The enumeration itself is [routeTurns] in `plugin-ui-api`: the guidance panel
 * counts the same instructions from the same list, so one route has one turn
 * order rather than two derivations. This file keeps only what is the preview's
 * own: which step the stepper shows, how a step is advanced and how its
 * distance reads.
 *
 * A route with instructions has one step per instruction. A route without any
 * instructions - the direct engine's geodesic line is one - has exactly one
 * step: arriving at the destination, at the route's own distance and at the
 * line's last point.
 */
fun routePlanTurns(
    state: RoutePlanState,
    strings: Strings,
): List<RouteTurnStep> {
    val option = state.selected ?: return emptyList()
    return routeTurns(
        geometry = option.geometry,
        distanceMeters = option.distanceMeters,
        durationSeconds = option.durationSeconds,
        instructions = option.instructions,
        arriveText = strings.resolve(ARRIVE_KEY),
    )
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

private const val ARRIVE_KEY = "route_planner_arrive"
private const val AT_KEY = "route_planner_turn_at"
