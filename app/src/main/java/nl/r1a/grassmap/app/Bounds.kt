package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint

/** The north-up box around [points], or null when there is nothing to fit. */
internal fun boundsOf(points: List<GeoPoint>?): BoundingBox? {
    if (points.isNullOrEmpty()) {
        return null
    }
    return BoundingBox(
        southWest = GeoPoint(points.minOf { it.latitude }, points.minOf { it.longitude }),
        northEast = GeoPoint(points.maxOf { it.latitude }, points.maxOf { it.longitude }),
    )
}
