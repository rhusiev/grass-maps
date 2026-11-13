package nl.r1a.grassmap.app

import nl.r1a.grassmap.navigation.trackFollowMeasuredSeconds
import nl.r1a.grassmap.navigation.trackFollowPlan
import nl.r1a.grassmap.navigation.trackFollowRoute
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.TrackFollowPlan
import nl.r1a.grassmap.plugin.api.model.TrackFollowStart
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.tracks.StoredTrack

// The app's wiring for following a stored track (T-0412). It is the only place
// that knows which numbers of a track feed the guidance seam: the geometry is
// every point of every segment in file order, and the pace is the track's own
// moving time, or its wall-clock span when no step counted as moving.
// `navigation` owns the choices and the route; this file only hands them the
// track. The track store is untouched: following reads a `StoredTrack` the
// analysis card already loaded and writes nothing back.

/**
 * The line the session follows: the track's points in file order, segments
 * joined.
 *
 * A segment boundary is a pause in the recording, not a gap in the walk, so the
 * joined line continues across it. The route model carries one polyline, and a
 * followed track has no second line to draw.
 */
internal fun StoredTrack.followLine(): List<GeoPoint> = track.segments.flatMap { segment -> segment.points.map { point -> point.position } }

/** The three start choices for this track, or null when it has no followable line. */
internal fun StoredTrack.followPlan(position: GeoPoint?): TrackFollowPlan? = trackFollowPlan(followLine(), position)

/** The route to follow from [start], built from the position the user has now. */
internal fun StoredTrack.followRoute(
    start: TrackFollowStart,
    position: GeoPoint?,
): Route? =
    trackFollowRoute(
        line = followLine(),
        start = start,
        position = position,
        measuredDurationSeconds = trackFollowMeasuredSeconds(analysis.movingTimeMillis, analysis.durationMillis),
    )

/** One open start sheet: the track, its choices and the choice picked so far. */
internal data class FollowTrackSheetState(
    val track: StoredTrack,
    val plan: TrackFollowPlan,
    val selected: TrackFollowStart = TrackFollowStart.DEFAULT,
)
