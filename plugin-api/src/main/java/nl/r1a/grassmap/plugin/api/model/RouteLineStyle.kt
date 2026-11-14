package nl.r1a.grassmap.plugin.api.model

/**
 * How the drawn route line is split into coloured, patterned runs (T-0408).
 *
 * The decision belongs to the route layer: which class each part of the line
 * falls in, the colour of that class, whether the part is dashed for an unpaved
 * surface, and whether it is striped because its way is steps. The map host
 * draws [runs] as they are and chooses nothing of its own, so the round-10
 * palette never has to be repeated in the drawing module.
 *
 * [runs] covers the route's geometry end to end when it is present: a run from
 * [RouteLineRun.startIndex] to [RouteLineRun.endIndex] includes both shape
 * points, so consecutive runs share one point and the line has no gap. An empty
 * list means the route carries no attribute this model can read, and the host
 * keeps its own single-colour line.
 */
data class RouteLineStyle(
    val runs: List<RouteLineRun> = emptyList(),
    val stripeColour: String? = null,
) {
    /** True when the host has a coloured line to draw instead of its plain one. */
    val isColoured: Boolean get() = runs.isNotEmpty()
}

/** One run of the route line, from [startIndex] to [endIndex] inclusive. */
data class RouteLineRun(
    val startIndex: Int,
    val endIndex: Int,
    val colour: String,
    val pattern: RouteLinePattern = RouteLinePattern.Solid,
)

/** The design's pattern for a run: a plain line, a dash, or the steps' stripes. */
enum class RouteLinePattern {
    /** A continuous line. */
    Solid,

    /** Dashes for an unpaved surface. */
    Dashed,

    /**
     * The design's stripes for steps. MapLibre cannot draw a 45° pattern along a
     * line (`map-style.md` also forbids `line-pattern`), so the host draws the
     * design's stripe ink as a dash over the run instead of a fifth colour.
     */
    Striped,
}
