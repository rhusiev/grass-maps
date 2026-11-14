package nl.r1a.grassmap.plugins.route.layer

import java.util.Locale

/*
 * The round-10 palette and the class decisions the route line uses (T-0408).
 *
 * Every value here is copied from the final design handoff, "Round 10 - chart
 * colors" (`design-handoff-2026-11-13.md`, section 4), which supersedes the old
 * slope, surface and way-type colours (section 3). The same palette is drawn as
 * the analysis chart's slope scale and surface strips in `ui-shell`; that module
 * keeps its own copy because the chart is UI and this module decides map
 * colours. A change to one must change both.
 */

/** The colour reads the route line can use. */
enum class RouteColourMode {
    /** Seven colours from steep descent to steep climb, on the hike scale. */
    Slope,

    /** Four colours: asphalt, paving, ground and gravel. */
    Surface,
}

/** The four surface classes the round-10 palette draws. */
enum class SurfaceClass(
    val isPaved: Boolean,
) {
    Asphalt(isPaved = true),
    Paving(isPaved = true),
    Ground(isPaved = false),
    Gravel(isPaved = false),
}

/**
 * The route-line colours for one theme, as `#RRGGBB` strings.
 *
 * Altitude and speed have no entry: the canon gives a slope scale, a surface
 * set and a way-type set, and no altitude or speed scale, so this layer does
 * not guess one. A mode that needs those stops waits for a design value.
 */
object RouteRound10Palette {
    val SLOPE_LIGHT =
        listOf(
            "#0D366B",
            "#256ABF",
            "#6DA7EC",
            "#D8D6D0",
            "#EE8C80",
            "#D9473E",
            "#8F1D1A",
        )

    /** Steeper is lighter in dark, as the design records. */
    val SLOPE_DARK =
        listOf(
            "#B7D3F6",
            "#5598E7",
            "#1F5AA3",
            "#7C7F84",
            "#B03A34",
            "#EE6F65",
            "#FFC4BC",
        )

    val SURFACE_LIGHT =
        mapOf(
            SurfaceClass.Asphalt to "#4A3AA7",
            SurfaceClass.Paving to "#E87BA4",
            SurfaceClass.Ground to "#008300",
            SurfaceClass.Gravel to "#EDA100",
        )

    val SURFACE_DARK =
        mapOf(
            SurfaceClass.Asphalt to "#9085E9",
            SurfaceClass.Paving to "#D55181",
            SurfaceClass.Ground to "#008300",
            SurfaceClass.Gravel to "#C98500",
        )

    /** The design's steps stripes, light and dark. */
    const val STRIPE_LIGHT = "#202124"
    const val STRIPE_DARK = "#E8EAED"

    /** The line's existing colour, kept for a part the palette cannot classify. */
    const val UNCLASSIFIED = "#2E7D32"

    /**
     * The design's slope scale, in percent on the hike profile. A value exactly
     * on a stop belongs to the bin below it, which is the chart's own rule.
     */
    val SLOPE_STOP_PERCENT = listOf(-15.0, -8.0, -3.0, 3.0, 8.0, 15.0)

    fun slopeColours(dark: Boolean): List<String> = if (dark) SLOPE_DARK else SLOPE_LIGHT

    fun surfaceColours(dark: Boolean): Map<SurfaceClass, String> = if (dark) SURFACE_DARK else SURFACE_LIGHT

    fun stripeColour(dark: Boolean): String = if (dark) STRIPE_DARK else STRIPE_LIGHT

    /** The slope bin for [slopePercent]: 0 is the steepest descent, 6 the steepest climb. */
    fun slopeBin(slopePercent: Double): Int = SLOPE_STOP_PERCENT.count { edge -> slopePercent > edge }

    /** The round-10 class for an OSM `surface` value, or null when it has none. */
    fun surfaceClass(value: String?): SurfaceClass? = SURFACES[value?.trim()?.lowercase(Locale.ROOT)]

    /** True when an OSM `highway` value is a way the design draws as steps. */
    fun isSteps(value: String?): Boolean = STEPS.contains(value?.trim()?.lowercase(Locale.ROOT))

    private val SURFACES =
        mapOf(
            "asphalt" to SurfaceClass.Asphalt,
            "paved" to SurfaceClass.Asphalt,
            "concrete" to SurfaceClass.Asphalt,
            "chipseal" to SurfaceClass.Asphalt,
            "paving_stones" to SurfaceClass.Paving,
            "sett" to SurfaceClass.Paving,
            "cobblestone" to SurfaceClass.Paving,
            "unhewn_cobblestone" to SurfaceClass.Paving,
            "bricks" to SurfaceClass.Paving,
            "gravel" to SurfaceClass.Gravel,
            "fine_gravel" to SurfaceClass.Gravel,
            "compacted" to SurfaceClass.Gravel,
            "pebblestone" to SurfaceClass.Gravel,
            "ground" to SurfaceClass.Ground,
            "earth" to SurfaceClass.Ground,
            "dirt" to SurfaceClass.Ground,
            "mud" to SurfaceClass.Ground,
            "grass" to SurfaceClass.Ground,
            "sand" to SurfaceClass.Ground,
        )

    private val STEPS = setOf("steps", "stairs")
}
