package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugins.route.warnings.RouteWarning
import nl.r1a.grassmap.plugins.route.warnings.RouteWarningKind
import nl.r1a.grassmap.ui.shell.RouteWarningGlyph
import nl.r1a.grassmap.ui.shell.RouteWarningRow

/** The panel's pictogram for one scanner kind (T-0409). */
internal fun RouteWarningKind.glyph(): RouteWarningGlyph =
    when (this) {
        RouteWarningKind.Ford -> RouteWarningGlyph.Ford
        RouteWarningKind.Steps -> RouteWarningGlyph.Steps
        RouteWarningKind.Barrier -> RouteWarningGlyph.Barrier
        RouteWarningKind.RailwayCrossing -> RouteWarningGlyph.RailwayCrossing
        RouteWarningKind.PrivateWay -> RouteWarningGlyph.PrivateWay
    }

/**
 * The scanner's list as the panel's rows. The scanner already ordered the
 * warnings by distance along the route and filtered them by profile, so the
 * mapping only names each kind and keeps the order.
 */
internal fun routeWarningRows(warnings: List<RouteWarning>): List<RouteWarningRow> =
    warnings.map { warning ->
        RouteWarningRow(
            glyph = warning.kind.glyph(),
            distanceAlongRouteMeters = warning.distanceAlongRouteMeters,
        )
    }

/**
 * The warning rows and the input that produced them. The panel shows [rows]
 * only while [route] and [profile] are still the plan's own input, so a route
 * change cannot leave the previous route's warnings on screen.
 */
internal data class RouteWarningsSnapshot(
    val route: Route?,
    val profile: RouteProfile,
    val rows: List<RouteWarningRow>,
) {
    /** [rows] when this snapshot belongs to [route] and [profile], else none. */
    fun rowsFor(
        route: Route?,
        profile: RouteProfile,
    ): List<RouteWarningRow> =
        if (this.route == route && this.profile == profile) {
            rows
        } else {
            emptyList()
        }

    companion object {
        val Empty = RouteWarningsSnapshot(route = null, profile = RouteProfile.Walk, rows = emptyList())
    }
}

/**
 * One scan per produced route and profile (T-0409).
 *
 * The plan re-publishes its state on every edit, so the scan is keyed on the
 * input rather than run per composition. A failure is not cached: the next
 * request for the same route asks again, and the caller shows no warnings in
 * the meantime. A cancelled scan leaves the previous success in place, because
 * the result is written only after [scan] returns.
 */
internal class RouteWarningCache(
    private val scan: suspend (Route, RouteProfile) -> Outcome<List<RouteWarning>>,
    private val log: (String) -> Unit,
) {
    private var scannedRoute: Route? = null
    private var scannedProfile: RouteProfile? = null
    private var cached: List<RouteWarningRow> = emptyList()

    suspend fun rows(
        route: Route?,
        profile: RouteProfile,
    ): List<RouteWarningRow> {
        if (route == null) {
            return emptyList()
        }
        if (route == scannedRoute && profile == scannedProfile) {
            return cached
        }
        return when (val outcome = scan(route, profile)) {
            is Outcome.Success -> {
                scannedRoute = route
                scannedProfile = profile
                cached = routeWarningRows(outcome.value)
                cached
            }

            is Outcome.Failure -> {
                log("route warnings unavailable: ${outcome.error}")
                emptyList()
            }
        }
    }
}
