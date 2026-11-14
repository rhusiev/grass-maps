package nl.r1a.grassmap.plugins.route.layer

/**
 * How the map draws the line over the route (T-0408).
 *
 * The design canon draws the line under active guidance as one plain line:
 * round 5's navigation screen and the handoff's navigation section. The slope
 * and surface read belongs to inspecting a planned route, so guidance asks for
 * [Plain] instead of inheriting a colour mode that has to be undone.
 */
sealed interface RouteLineDrawing {
    /**
     * One plain line in the map's own route colour. The style carries no runs,
     * so the host keeps its single-colour line.
     */
    data object Plain : RouteLineDrawing

    /** One coloured, patterned run per route segment, from [mode]'s round-10 scale. */
    data class Coloured(
        val mode: RouteColourMode = RouteColourMode.Slope,
        val dark: Boolean = false,
    ) : RouteLineDrawing
}
