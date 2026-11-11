package nl.r1a.grassmap.plugin.api.model

import nl.r1a.grassmap.plugin.api.provider.CoordinateParser

/**
 * What a coordinate typed into the planner means (T-0304).
 *
 * The planner never writes a second parser: the same [CoordinateParser] the
 * search field asks turns the text into a point, so the notations the search
 * accepts and the notations the planner accepts cannot drift apart. The
 * parser's refusals are kept as [Refused] instead of a guess, and blank text is
 * [Blank] so the field does not report an error before the user typed
 * anything.
 */
sealed interface RoutePointEntry {
    /** Only whitespace; the field keeps its state and shows nothing. */
    data object Blank : RoutePointEntry

    /** The parser refused the text; no route point changes. */
    data object Refused : RoutePointEntry

    /**
     * The point the text denotes. [label] is the trimmed text the user picked
     * it from, which [RoutePlanPoint] keeps as its label.
     */
    data class Point(
        val position: GeoPoint,
        val label: String,
    ) : RoutePointEntry
}

/** Reads one typed route point through the parser the app wired. */
fun routePointEntry(
    text: String,
    parser: CoordinateParser,
): RoutePointEntry {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) {
        return RoutePointEntry.Blank
    }
    val position = parser.parse(trimmed) ?: return RoutePointEntry.Refused
    return RoutePointEntry.Point(position, trimmed)
}
