package nl.r1a.grassmap.plugins.proximity.alerts

import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.ProximityPlaceKind
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How close the traveller must come to each category before it is announced
 * (T-0413).
 *
 * The design canon (handoff 2026-11-13, round 6) says proximity alerts use the
 * blue info banner and that place kinds are chips the user picks. It states no
 * distance for any of the three categories, so these radii are **chosen** here,
 * one constant per category, and not derived from the canon. They are not
 * profile-dependent, because the canon gives proximity no riding speed:
 *
 * | Category | Radius | Walking (5 km/h) | Cycling (18 km/h) |
 * |---|---|---|---|
 * | Favourite | 200 m | 2 min 24 s | 40 s |
 * | Track waypoint | 100 m | 1 min 12 s | 20 s |
 * | Chosen place kind | 250 m | 3 min | 50 s |
 *
 * A favourite is a place the traveller already knows and named, so the notice
 * is short and the radius is moderate. A track waypoint sits on the line being
 * followed, so the notice is the final heads-up rather than a plan. A chosen
 * place kind is a place worth a detour, so it gets the longest reach. The
 * travelling times use the product speeds, not the navigation gate's
 * conservative ones, and only describe the size of each radius.
 *
 * [RELEASE_METERS] is the hysteresis: a place is announced once when it first
 * comes inside its radius, and is armed again only after the traveller is at
 * least this far beyond the radius. Without the margin a fix wobbling on the
 * boundary would announce twice for one approach. With it, one approach
 * announces once and a real second approach, after leaving the radius,
 * announces again.
 *
 * [QUERY_METERS] and [REFRESH_METERS] bound the lookup the source performs:
 * the schedule asks for places inside a box around the fix every
 * [REFRESH_METERS] of travel. The box is wider than the largest radius plus
 * one refresh step, so a place cannot enter a radius between two lookups
 * unseen.
 */
object ProximityRadius {
    /** Chosen, not canon: see the table above. */
    const val FAVOURITE_METERS: Double = 200.0

    /** Chosen, not canon: see the table above. */
    const val TRACK_WAYPOINT_METERS: Double = 100.0

    /** Chosen, not canon: see the table above. */
    const val POI_METERS: Double = 250.0

    /** How far beyond the radius the latch is released. */
    const val RELEASE_METERS: Double = 50.0

    /** The half-size of the box the source is asked about. */
    const val QUERY_METERS: Double = 500.0

    /** How far the fix must move before the source is asked again. */
    const val REFRESH_METERS: Double = 200.0

    fun metersFor(kind: ProximityPlaceKind): Double =
        when (kind) {
            ProximityPlaceKind.Favourite -> FAVOURITE_METERS
            ProximityPlaceKind.TrackWaypoint -> TRACK_WAYPOINT_METERS
            ProximityPlaceKind.Poi -> POI_METERS
        }
}

/** The ground distance between two points, in metres. */
object ProximityDistance {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun meters(
        from: GeoPoint,
        to: GeoPoint,
    ): Double {
        val fromLat = Math.toRadians(from.latitude)
        val toLat = Math.toRadians(to.latitude)
        val deltaLat = toLat - fromLat
        val deltaLon = Math.toRadians(to.longitude - from.longitude)
        val a =
            sin(deltaLat / 2) * sin(deltaLat / 2) +
                cos(fromLat) * cos(toLat) * sin(deltaLon / 2) * sin(deltaLon / 2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

/**
 * The north-up box around a fix that reaches [meters] in every direction.
 *
 * The longitude step uses the cosine of the fix's own latitude, so the box
 * covers the same ground distance near the equator and near the pole. Near the
 * poles the cosine shrinks towards zero; there the box takes the whole
 * longitude range instead of a step larger than the world. A box that would
 * cross the date line is clamped to it, so the result is always a valid
 * [BoundingBox]; a fix within [meters] of the date line gets the part of its
 * box on this side only, which the next refresh repairs.
 */
object ProximityBox {
    private const val METERS_PER_DEGREE_LATITUDE = 111_320.0
    private const val MAX_LATITUDE = 90.0
    private const val MAX_LONGITUDE = 180.0
    private const val MIN_LONGITUDE_COSINE = 1e-9

    fun around(
        center: GeoPoint,
        meters: Double,
    ): BoundingBox {
        require(meters.isFinite() && meters > 0.0) { "the box must reach a positive distance" }
        val latitudeDelta = meters / METERS_PER_DEGREE_LATITUDE
        val cosine = cos(Math.toRadians(center.latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)))
        val longitudeDelta = if (cosine < MIN_LONGITUDE_COSINE) MAX_LONGITUDE else meters / (METERS_PER_DEGREE_LATITUDE * cosine)
        return BoundingBox(
            southWest =
                GeoPoint(
                    latitude = (center.latitude - latitudeDelta).coerceIn(-MAX_LATITUDE, MAX_LATITUDE),
                    longitude = (center.longitude - longitudeDelta).coerceIn(-MAX_LONGITUDE, MAX_LONGITUDE),
                ),
            northEast =
                GeoPoint(
                    latitude = (center.latitude + latitudeDelta).coerceIn(-MAX_LATITUDE, MAX_LATITUDE),
                    longitude = (center.longitude + longitudeDelta).coerceIn(-MAX_LONGITUDE, MAX_LONGITUDE),
                ),
        )
    }
}
