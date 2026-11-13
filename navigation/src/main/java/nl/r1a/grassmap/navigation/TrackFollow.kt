package nl.r1a.grassmap.navigation

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.TrackFollowOption
import nl.r1a.grassmap.plugin.api.model.TrackFollowPlan
import nl.r1a.grassmap.plugin.api.model.TrackFollowStart
import nl.r1a.grassmap.plugin.api.provider.Route

// Following a track (T-0412): the start choices and the route the existing
// session then guides along.
//
// A track is not a plan. It carries geometry and, when the file was recorded,
// its own timing; it carries no legs and no maneuvers. The route built here
// therefore has one line and no instructions, which is exactly what
// RouteProgress needs to count the track's time down proportionally: a track
// without turns still follows the line, and the guidance panel shows the
// remaining distance and time without inventing a turn.
//
// The line is the caller's: one ordered list of points, the track's segments
// joined in order. A segment boundary is a pause in the file, not a break in
// the line, so following continues across it. The route's distance is the
// locator's own distance space, so the follower's remaining distance subtracts
// from it without a second conversion.
//
// No second follower is built here. The route is handed to NavigationSession,
// which snaps every fix to this line with the same PolylineLocator used below
// and applies the same off-route and arrival rules as a routed trip.

/**
 * The three start choices for [line], or null when the line cannot be followed.
 *
 * A line needs two points and a non-zero length: a one-point track, an empty
 * track and a track whose points all sit on one spot have nothing to follow and
 * get no plan rather than a sheet with three dead choices.
 *
 * The distances are ground distances from [position] to each choice's join
 * point. Without a position the distances are null and [TrackFollowStart.Nearest]
 * falls back to the line's first point, which is the only join the geometry can
 * name without one.
 */
fun trackFollowPlan(
    line: List<GeoPoint>,
    position: GeoPoint?,
): TrackFollowPlan? {
    val locator = followableLine(line) ?: return null
    val projection = position?.let(locator::locate)
    val nearestJoin = projection?.position ?: line.first()
    val options =
        listOf(
            TrackFollowOption(
                start = TrackFollowStart.Start,
                joinPoint = line.first(),
                joinDistanceMeters = position?.let { GeoMath.distanceMeters(it, line.first()) },
            ),
            TrackFollowOption(
                start = TrackFollowStart.Reversed,
                joinPoint = line.last(),
                joinDistanceMeters = position?.let { GeoMath.distanceMeters(it, line.last()) },
            ),
            TrackFollowOption(
                start = TrackFollowStart.Nearest,
                joinPoint = nearestJoin,
                joinDistanceMeters = position?.let { GeoMath.distanceMeters(it, nearestJoin) },
            ),
        )
    return TrackFollowPlan(options = options)
}

/**
 * The route the session follows for [line] from [start].
 *
 * [position] decides only [TrackFollowStart.Nearest]: the route then begins at
 * the point of the line closest to the user and keeps the line's direction, so
 * the part already behind them is not guided again. Without a position that
 * choice begins at the line's first point. A nearest join at the very end leaves
 * nothing to walk, and the route keeps that last point twice so the session can
 * still start and arrive at once instead of refusing a line of one point.
 *
 * [measuredDurationSeconds] is the track's own time for the whole line, its
 * moving time when the file carried timestamps. The route's duration is that
 * time scaled to the part actually followed. A track with no timing, and a
 * measurement that is not positive, fall back to [FALLBACK_SPEED_METERS_PER_SECOND]
 * because the file holds no pace to offer.
 */
fun trackFollowRoute(
    line: List<GeoPoint>,
    start: TrackFollowStart,
    position: GeoPoint?,
    measuredDurationSeconds: Double? = null,
): Route? {
    val locator = followableLine(line) ?: return null
    val totalMeters = locator.totalMeters()
    val alongMeters =
        if (start == TrackFollowStart.Nearest) {
            position?.let { locator.locate(it)?.alongMeters } ?: 0.0
        } else {
            0.0
        }
    val geometry =
        when (start) {
            TrackFollowStart.Start -> line.toList()
            TrackFollowStart.Reversed -> line.asReversed().toList()
            TrackFollowStart.Nearest -> locator.pointsBetween(alongMeters, totalMeters)
        }
    if (geometry.size < MINIMUM_LINE_POINTS) {
        return null
    }
    val followedMeters =
        if (start == TrackFollowStart.Nearest) {
            totalMeters - alongMeters
        } else {
            totalMeters
        }
    return Route(
        geometry = geometry,
        distanceMeters = followedMeters,
        durationSeconds = followDurationSeconds(measuredDurationSeconds, followedMeters, totalMeters),
    )
}

/**
 * The whole line's own duration for the caller: the track's moving time when a
 * step counted as moving, and otherwise its wall-clock span. A track with no
 * timing, and one whose whole recording was a stop, has no pace and returns
 * null.
 */
fun trackFollowMeasuredSeconds(
    movingMillis: Long?,
    durationMillis: Long?,
): Double? {
    val millis = movingMillis?.takeIf { it > 0L } ?: durationMillis?.takeIf { it > 0L } ?: return null
    return millis / MILLIS_PER_SECOND
}

private fun followableLine(line: List<GeoPoint>): PolylineLocator? {
    if (line.size < MINIMUM_LINE_POINTS) {
        return null
    }
    val locator = PolylineLocator(line)
    return locator.takeIf { it.totalMeters() > 0.0 }
}

private fun followDurationSeconds(
    measuredDurationSeconds: Double?,
    followedMeters: Double,
    totalMeters: Double,
): Double {
    val measured = measuredDurationSeconds?.takeIf { it.isFinite() && it > 0.0 }
    return if (measured != null) {
        measured * (followedMeters / totalMeters)
    } else {
        followedMeters / FALLBACK_SPEED_METERS_PER_SECOND
    }
}

/**
 * The pace a track with no timing is walked at. It is chosen, not derived: the
 * file has no measured pace, and the product's walk profile is the everyday
 * case. 1.25 m/s is 4.5 km/h.
 */
internal const val FALLBACK_SPEED_METERS_PER_SECOND: Double = 1.25

/** A line needs a start and an end to be followed. */
private const val MINIMUM_LINE_POINTS = 2

private const val MILLIS_PER_SECOND = 1_000.0
