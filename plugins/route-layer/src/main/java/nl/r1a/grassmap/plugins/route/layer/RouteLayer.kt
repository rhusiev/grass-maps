package nl.r1a.grassmaps.plugins.route.layer

import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.error.onFailure
import nl.r1a.grassmaps.plugin.api.host.MapHost
import nl.r1a.grassmaps.plugin.api.model.RouteLineStyle
import nl.r1a.grassmaps.plugin.api.provider.Route

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
    /**
     * The point count of the route the host last accepted, 0 after a clear.
     * A debug probe reads it to know the map holds the route before a capture.
     */
    @Volatile
    var drawnPointCount: Int = 0
        private set

    /**
     * How many draws and clears the host accepted. A probe that sees it change
     * knows the line was drawn or cleared again since its last read.
     */
    @Volatile
    var acceptedDrawCount: Int = 0
        private set

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
        val outcome = map.setRoute(route, style)
        if (outcome is Outcome.Success) {
            drawnPointCount = route?.geometry?.size ?: 0
            acceptedDrawCount += 1
        }
        return outcome.onFailure { error -> log("route layer not drawn: $error") }
    }
}
