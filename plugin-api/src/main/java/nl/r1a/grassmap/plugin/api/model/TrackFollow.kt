package nl.r1a.grassmap.plugin.api.model

/**
 * Where following a track begins (T-0412, design round 6).
 *
 * A track is a line with a direction the file chose, so following it has three
 * honest starting places. [Start] follows the line as the file wrote it from its
 * first point. [Reversed] follows the same line the other way from its last
 * point. [Nearest] joins the line at the point closest to the user and keeps the
 * file's direction from there.
 *
 * [Nearest] is the default: a tap on a track usually happens where the user is
 * standing, and asking for the whole line would send them to a point they have
 * already left.
 */
enum class TrackFollowStart {
    Start,
    Reversed,
    Nearest,
    ;

    companion object {
        /** The choice the start sheet selects when it opens. */
        val DEFAULT: TrackFollowStart = Nearest
    }
}

/**
 * One choice of the start sheet: where following begins and how far the user
 * must walk to get there.
 *
 * [joinPoint] is the first point of the route this choice follows, so the sheet
 * can show it and the distance is measured to exactly that point. An unknown
 * position leaves [joinDistanceMeters] null rather than a guess; a null reads as
 * "distance unknown" and is not drawn.
 */
data class TrackFollowOption(
    val start: TrackFollowStart,
    val joinPoint: GeoPoint,
    val joinDistanceMeters: Double?,
)

/**
 * The three places following one track can begin, in the design's order: from
 * the start, from the end reversed, and from the nearest point.
 *
 * The plan is the geometry's answer, not the sheet's: the sheet draws these
 * options and starts the route of the one the user picked. A track with no line
 * at all has no plan, because no start can be offered for it.
 */
data class TrackFollowPlan(
    val options: List<TrackFollowOption>,
    val defaultStart: TrackFollowStart = TrackFollowStart.DEFAULT,
) {
    init {
        require(options.isNotEmpty()) { "a follow plan offers at least one start" }
        require(options.distinctBy { it.start }.size == options.size) { "a start is offered once" }
    }

    /** The choice for [start], or null when this plan does not offer it. */
    fun option(start: TrackFollowStart): TrackFollowOption? = options.firstOrNull { it.start == start }
}
