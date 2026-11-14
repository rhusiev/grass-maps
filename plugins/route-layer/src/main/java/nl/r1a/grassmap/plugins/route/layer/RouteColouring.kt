package nl.r1a.grassmap.plugins.route.layer

import nl.r1a.grassmap.plugin.api.model.AttributeValue
import nl.r1a.grassmap.plugin.api.model.RouteLinePattern
import nl.r1a.grassmap.plugin.api.model.RouteLineRun
import nl.r1a.grassmap.plugin.api.model.RouteLineStyle
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteAttributes
import nl.r1a.grassmap.plugin.api.provider.RouteSegment

/**
 * The route line's colour and pattern decision (T-0408).
 *
 * The routing engine's annotate pass already cut the line into segments and put
 * `grade`, `surface` and `highway` on them, so nothing here re-reads the map or
 * runs a second analysis. Each segment becomes one run: its colour from the
 * chosen mode's round-10 scale, its pattern from its own way and surface.
 *
 * The runs always partition the geometry: an uncovered part keeps the route's
 * existing colour with no pattern rather than guessing a class, because a gap
 * in a continuous route line reads as a broken route. A route whose segments
 * carry none of the three attributes returns an empty style, and the map keeps
 * its plain single-colour line.
 *
 * Tracks are not coloured here. A track has no surface and no smoothness
 * attribute at all (T-0406's recorded gap), and the track layer receives
 * geometry without heights, so neither surface nor slope can be decided for it
 * without inventing data.
 */
object RouteColouring {
    fun style(
        route: Route?,
        mode: RouteColourMode,
        dark: Boolean,
    ): RouteLineStyle {
        if (route == null || route.geometry.size < MINIMUM_GEOMETRY_POINTS || route.segments.isEmpty()) {
            return RouteLineStyle()
        }
        if (route.segments.none(::carriesClass)) {
            return RouteLineStyle()
        }
        val last = route.geometry.lastIndex
        val runs = mutableListOf<RouteLineRun>()
        var covered = NO_COVER
        for (segment in route.segments.sortedBy { it.startIndex }) {
            val start = segment.startIndex.coerceIn(0, last)
            val end = segment.endIndex.coerceIn(0, last)
            if (end - start < MINIMUM_RUN_POINTS - 1) {
                continue
            }
            if (start > covered + 1) {
                runs += run(covered.coerceAtLeast(0), start, null, mode, dark)
            }
            runs += run(start, end, segment, mode, dark)
            covered = maxOf(covered, end)
        }
        if (covered < last) {
            runs += run(covered.coerceAtLeast(0), last, null, mode, dark)
        }
        return RouteLineStyle(runs = runs, stripeColour = RouteRound10Palette.stripeColour(dark))
    }

    /**
     * The pattern a segment's own way and surface carry: steps are striped, an
     * unpaved surface is dashed, everything else is solid. A steps way is the
     * stronger mark, so it wins when the surface is also unpaved.
     */
    fun pattern(segment: RouteSegment?): RouteLinePattern {
        if (segment == null) {
            return RouteLinePattern.Solid
        }
        if (RouteRound10Palette.isSteps(text(segment, RouteAttributes.HIGHWAY))) {
            return RouteLinePattern.Striped
        }
        val surface = RouteRound10Palette.surfaceClass(text(segment, RouteAttributes.SURFACE))
        return if (surface != null && !surface.isPaved) RouteLinePattern.Dashed else RouteLinePattern.Solid
    }

    /** The run's colour for [mode], or the unclassified line colour. */
    fun colour(
        segment: RouteSegment?,
        mode: RouteColourMode,
        dark: Boolean,
    ): String {
        if (segment == null) {
            return RouteRound10Palette.UNCLASSIFIED
        }
        return when (mode) {
            RouteColourMode.Slope -> {
                val grade = (segment.attributes[RouteAttributes.GRADE] as? AttributeValue.NumberValue)?.value
                if (grade == null) {
                    RouteRound10Palette.UNCLASSIFIED
                } else {
                    RouteRound10Palette.slopeColours(dark)[RouteRound10Palette.slopeBin(grade)]
                }
            }

            RouteColourMode.Surface -> {
                val surface = RouteRound10Palette.surfaceClass(text(segment, RouteAttributes.SURFACE))
                if (surface == null) {
                    RouteRound10Palette.UNCLASSIFIED
                } else {
                    RouteRound10Palette.surfaceColours(dark).getValue(surface)
                }
            }
        }
    }

    private fun run(
        start: Int,
        end: Int,
        segment: RouteSegment?,
        mode: RouteColourMode,
        dark: Boolean,
    ): RouteLineRun =
        RouteLineRun(
            startIndex = start,
            endIndex = end,
            colour = colour(segment, mode, dark),
            pattern = pattern(segment),
        )

    /**
     * Whether a segment carries any attribute the line can be read by. A route
     * with none keeps the map's plain line rather than becoming one unclassified
     * run.
     */
    private fun carriesClass(segment: RouteSegment): Boolean =
        listOf(RouteAttributes.GRADE, RouteAttributes.SURFACE, RouteAttributes.HIGHWAY)
            .any { key -> segment.attributes.containsKey(key) }

    private fun text(
        segment: RouteSegment,
        key: String,
    ): String? = (segment.attributes[key] as? AttributeValue.TextValue)?.value?.takeIf { it.isNotBlank() }

    private const val MINIMUM_GEOMETRY_POINTS = 2
    private const val MINIMUM_RUN_POINTS = 2

    /** No geometry point is covered yet. */
    private const val NO_COVER = -1
}
