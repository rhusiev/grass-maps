package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.tracks.TrackId

/**
 * What one Save tap on the route preview produced (T-0411).
 *
 * This path has no picker: the route is copied into the library, so a dismissal
 * cannot happen and there is no third case beside the save and the typed error.
 */
sealed interface RouteSaveResult {
    /** [id] is the record's document hash; [name] is null when it had no name. */
    data class Saved(
        val id: TrackId,
        val name: String?,
    ) : RouteSaveResult

    data class Failed(
        val error: AppError,
    ) : RouteSaveResult
}

/** What a preview save reports, resolved by the app's own string service (T-0411). */
internal const val ROUTE_SAVED_KEY = "route_saved"
internal const val ROUTE_SAVE_FAILED_KEY = "route_save_failed"

/**
 * The name a saved route takes: the destination's label (T-0411).
 *
 * The plan has no name of its own. Its start, vias and destination each keep the
 * text the point was picked from, so the destination is the text the preview
 * would name the route by, and a blank label stores the route unnamed rather
 * than under a made-up name.
 */
fun RoutePlanState.routeName(): String? = destination?.label?.trim()?.takeIf { name -> name.isNotEmpty() }

/**
 * The one entry point from the preview's Save action to the library (T-0411).
 *
 * The plan carries the selected route's geometry and the effective options, so
 * the sidecar records what the displayed route was computed with rather than the
 * profile defaults. A refusal is the typed error the save reported: a plan with
 * no route yet saves nothing.
 */
suspend fun saveRoute(
    access: TrackAccess,
    plan: RoutePlanState,
): RouteSaveResult =
    when (val saved = access.savePlannedRoute(plan, plan.routeName())) {
        is Outcome.Success -> RouteSaveResult.Saved(saved.value.id, saved.value.name)
        is Outcome.Failure -> RouteSaveResult.Failed(saved.error)
    }
