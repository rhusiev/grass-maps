package nl.r1a.grassmap.plugin.api.host

import nl.r1a.grassmap.plugin.api.provider.RouteOptions
import nl.r1a.grassmap.plugin.api.provider.RouteProfile

/**
 * The routing options the host holds for each profile (T-0308).
 *
 * The planner asks this seam for the profile it is about to request, so the
 * settings model stays in the app and no plugin names a settings file. An
 * unwired host answers with the defaults.
 */
fun interface RoutingOptionsAccess {
    fun forProfile(profile: RouteProfile): RouteOptions
}

/** The source a host uses before the settings model is wired. */
object NoRoutingOptionsAccess : RoutingOptionsAccess {
    override fun forProfile(profile: RouteProfile): RouteOptions = RouteOptions()
}
