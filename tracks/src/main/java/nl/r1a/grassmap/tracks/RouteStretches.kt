package nl.r1a.grassmap.tracks

import nl.r1a.grassmap.plugin.api.model.AttributeValue
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.RouteAttributes
import nl.r1a.grassmap.plugin.api.provider.RouteSegment

/**
 * One stretch of a line between two distances along its elevation profile,
 * with the attributes the routing engine's annotate pass gave that part (T-0406).
 *
 * [startMeters] and [endMeters] lie on the profile's own distance axis, so the
 * stretch sits exactly where the chart draws it. [surface] and [wayType] are the
 * engine's own attribute values; either is null when the engine had no value
 * there, and the strips then leave that part of the line out rather than guess.
 */
data class RouteStretch(
    val startMeters: Double,
    val endMeters: Double,
    val surface: String? = null,
    val wayType: String? = null,
) {
    /** The ground distance this stretch covers. */
    val distanceMeters: Double get() = (endMeters - startMeters).coerceAtLeast(0.0)
}

/**
 * Reads the engine's annotated [RouteSegment]s as stretches of an
 * [ElevationProfile] (T-0406).
 *
 * The segmentation is the engine's: the annotate pass already cut the line into
 * one segment per graph edge and put the surface and highway values on it, so
 * nothing here re-reads the map or re-cuts the line. A segment's own shape range
 * places it on the profile, and the cumulative haversine distance of those
 * shape indices gives the two ends. That is the same step and the same constant
 * the profile measures with, so the strips cannot drift from the chart's axis;
 * a strip's percentage is therefore the summed length of the engine's segments
 * of that class over the summed length of the classified line.
 *
 * A segment whose indices fall outside the geometry is clamped, a stretch that
 * ends before it starts is dropped, and a profile with no line or a line with
 * no segments gives no stretches. Adjacent segments are kept apart here: the
 * shell merges them for drawing, which keeps this model a straight read of the
 * engine's segments.
 */
class RouteStretches {
    /**
     * The stretches of [profile] the annotated [segments] cover, in distance
     * order.
     *
     * A stretch is clamped to the profile's own span, because the first and last
     * drawn point are what the chart can show even when the engine's segment
     * starts at the very first geometry point and ends at the very last.
     */
    fun of(
        profile: ElevationProfile,
        geometry: List<GeoPoint>,
        segments: List<RouteSegment>,
    ): List<RouteStretch> {
        if (!profile.isDrawable || segments.isEmpty() || geometry.size < MINIMUM_GEOMETRY_POINTS) {
            return emptyList()
        }
        val distances = geometryDistances(geometry)
        val first = profile.samples.first().distanceMeters
        val last = profile.samples.last().distanceMeters
        if (last - first <= 0.0) {
            return emptyList()
        }
        return segments.mapNotNull { segment ->
            val start = distances[segment.startIndex.coerceIn(0, distances.lastIndex)]
            val end = distances[segment.endIndex.coerceIn(0, distances.lastIndex)]
            val low = minOf(start, end).coerceIn(first, last)
            val high = maxOf(start, end).coerceIn(first, last)
            if (high - low <= 0.0) {
                return@mapNotNull null
            }
            RouteStretch(
                startMeters = low,
                endMeters = high,
                surface = text(segment, RouteAttributes.SURFACE),
                wayType = text(segment, RouteAttributes.HIGHWAY),
            )
        }
    }

    /** The cumulative haversine distance at each geometry point. */
    private fun geometryDistances(geometry: List<GeoPoint>): DoubleArray {
        val distances = DoubleArray(geometry.size)
        for (index in 1 until geometry.size) {
            distances[index] = distances[index - 1] + distanceMeters(geometry[index - 1], geometry[index])
        }
        return distances
    }

    /** One attribute as text; a missing value, or one of another type, is null. */
    private fun text(
        segment: RouteSegment,
        key: String,
    ): String? = (segment.attributes[key] as? AttributeValue.TextValue)?.value?.takeIf { it.isNotBlank() }

    private companion object {
        const val MINIMUM_GEOMETRY_POINTS = 2
    }
}
