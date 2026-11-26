package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.RoutePlanFailure
import nl.r1a.grassmaps.plugin.api.model.RoutePlanState
import nl.r1a.grassmaps.plugin.api.model.RoutePlanStatus

/**
 * Which surface the planner sheet composes (round 8 `x-route-preview`,
 * `x-route-stops`, round 10 `c-route-details`, round 6 `r-prefs-route`).
 * [Summary] is the route preview, [Stops] the stop editor, [Details] the chart
 * and [Preferences] the one-route preferences.
 */
enum class RoutePlanSheetView {
    Summary,
    Stops,
    Details,
    Preferences,
}

/** The view the planner sheet opens with: the route preview. */
val DEFAULT_ROUTE_PLAN_SHEET_VIEW: RoutePlanSheetView = RoutePlanSheetView.Summary

/** One block of the sheet's scrolling content. */
enum class RoutePlanSection {
    EditableHeader,
    StopList,
    Status,
    ModeChips,
    Summary,
    Alternatives,
    Chart,
    Turns,
    Warnings,
    PreferencesEntry,
    Preferences,
    RecentRoutes,
}

/**
 * One button of the sheet's pinned bottom bar. [next] is the view the sheet
 * shows after the press; null keeps the view, because Start hands the route
 * to navigation. A [primary] button takes the accent fill and the wider share
 * of its row.
 */
enum class RoutePlanBarAction(
    val next: RoutePlanSheetView?,
    val primary: Boolean,
) {
    Stops(RoutePlanSheetView.Stops, primary = false),
    Details(RoutePlanSheetView.Details, primary = false),
    Start(null, primary = true),
    HideDetails(RoutePlanSheetView.Summary, primary = false),
    Done(RoutePlanSheetView.Summary, primary = true),
    Reset(RoutePlanSheetView.Summary, primary = false),
    Apply(RoutePlanSheetView.Summary, primary = true),
}

/** The sheet's scrolling sections in drawing order, and its pinned bar. */
data class RoutePlanSheetLayout(
    val sections: List<RoutePlanSection>,
    val bar: List<RoutePlanBarAction>,
)

/** What the planner sheet composes for [view] and the plan's state. */
fun routePlanSheetLayout(
    view: RoutePlanSheetView,
    state: RoutePlanState,
): RoutePlanSheetLayout {
    val ready = state.status == RoutePlanStatus.Ready
    return when {
        state.status == RoutePlanStatus.Idle -> {
            RoutePlanSheetLayout(emptyList(), emptyList())
        }

        view == RoutePlanSheetView.Preferences -> {
            RoutePlanSheetLayout(
                listOf(RoutePlanSection.PreferencesEntry, RoutePlanSection.Preferences),
                listOf(RoutePlanBarAction.Reset, RoutePlanBarAction.Apply),
            )
        }

        view == RoutePlanSheetView.Stops -> {
            val status = if (ready) emptyList() else pendingSections(state)
            RoutePlanSheetLayout(
                listOf(RoutePlanSection.EditableHeader, RoutePlanSection.StopList) + status,
                listOf(RoutePlanBarAction.Done),
            )
        }

        !ready -> {
            RoutePlanSheetLayout(pendingSections(state), emptyList())
        }

        view == RoutePlanSheetView.Details -> {
            RoutePlanSheetLayout(
                listOf(RoutePlanSection.Summary, RoutePlanSection.Chart, RoutePlanSection.Turns),
                listOf(RoutePlanBarAction.HideDetails, RoutePlanBarAction.Start),
            )
        }

        else -> {
            RoutePlanSheetLayout(
                listOf(
                    RoutePlanSection.ModeChips,
                    RoutePlanSection.Summary,
                    RoutePlanSection.Alternatives,
                    RoutePlanSection.Warnings,
                    RoutePlanSection.PreferencesEntry,
                ),
                listOf(RoutePlanBarAction.Stops, RoutePlanBarAction.Details, RoutePlanBarAction.Start),
            )
        }
    }
}

/**
 * A plan without a route shows its status. The missing-regions card replaces
 * the rest of the sheet; otherwise preferences stay reachable, because an
 * applied override can leave the reroute planning or failed and the user must
 * be able to reset it there (T-0421). Recent routes help while an end is picked.
 */
private fun pendingSections(state: RoutePlanState): List<RoutePlanSection> =
    when {
        state.status == RoutePlanStatus.Failed && state.failure == RoutePlanFailure.RegionsMissing && state.missing != null -> {
            listOf(RoutePlanSection.Status)
        }

        state.pickingRole != null -> {
            listOf(RoutePlanSection.Status, RoutePlanSection.PreferencesEntry, RoutePlanSection.RecentRoutes)
        }

        else -> {
            listOf(RoutePlanSection.Status, RoutePlanSection.PreferencesEntry)
        }
    }

/** The view Back returns to, or null when Back closes the plan. */
fun routePlanBackView(view: RoutePlanSheetView): RoutePlanSheetView? =
    if (view == RoutePlanSheetView.Summary) null else RoutePlanSheetView.Summary
