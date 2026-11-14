package nl.r1a.grassmap.plugins.route.layer

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.error.onFailure
import nl.r1a.grassmap.plugin.api.host.MapHost
import nl.r1a.grassmap.plugin.api.model.RouteLineStyle
import nl.r1a.grassmap.plugin.api.provider.Route

/**
 * Draws the line over the planned or guided route with the colour and pattern
 * decision (T-0408), or the canon's plain line when guidance is active.
 *
 * The controller owns no state: [host] is the newest bound map surface. It hands
 * the map one complete [nl.r1a.grassmap.plugin.api.model.RouteLineStyle] with
 * every draw, so a redraw never mixes the new route with the previous style. A
 * surface that is gone is [AppError.NotReady] and never an exception.
 */
class RouteLayer(
    private val host: () -> MapHost?,
    private val log: (String) -> Unit = {},
) {
    /** Draws [route] as [drawing] asks, or clears the route when it is null. */
    suspend fun draw(
        route: Route?,
        drawing: RouteLineDrawing,
    ): Outcome<Unit> {
        val map = host() ?: return Outcome.Failure(AppError.NotReady)
        val style =
            when (drawing) {
                RouteLineDrawing.Plain -> RouteLineStyle()
                is RouteLineDrawing.Coloured -> RouteColouring.style(route, drawing.mode, drawing.dark)
            }
        return map.setRoute(route, style).onFailure { error -> log("route layer not drawn: $error") }
    }
}
