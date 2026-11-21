package nl.r1a.grassmaps.routing

import nl.r1a.grassmaps.plugin.api.model.CatalogPartKind
import nl.r1a.grassmaps.plugin.api.model.CatalogRegion
import nl.r1a.grassmaps.plugin.api.model.CatalogRegionKind
import nl.r1a.grassmaps.plugin.api.model.DownloadsData
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.MissingRegion
import nl.r1a.grassmaps.plugin.api.model.MissingRegionPart
import nl.r1a.grassmaps.plugin.api.model.MissingRegions
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Picks the oblasts a route needs and the phone does not have (T-0511).
 *
 * The catalogue declares one bounding box per region and no boundary the app
 * can test against: the `outline` is a file on the server. Boxes of
 * neighbouring oblasts overlap, so the choice is made from the route's stops,
 * not from the line between them:
 *
 * 1. A stop is covered when any region whose box contains it is installed with
 *    its routing part. An overlap can therefore hide a missing oblast, and can
 *    never invite a download the route does not need.
 * 2. An uncovered stop needs the region whose box contains it and whose centre
 *    is nearest. A stop outside every box needs nothing, because no package
 *    can serve it.
 *
 * The straight segment between consecutive stops is sampled every
 * [SEGMENT_SAMPLE_METERS] and each sample follows the same rule, so an oblast
 * the route must cross between covered stops is listed too. The line is not
 * the road, so a detour through an oblast the segment misses is not detected.
 * Callers ask only after a route failed, so the samples cost nothing on a
 * route that works.
 */
object MissingRegionFinder {
    /** The gap between samples on a straight segment: chosen, not derived. A smaller gap only matters for an oblast narrower than 5 km. */
    const val SEGMENT_SAMPLE_METERS = 5_000.0
    private const val METERS_PER_DEGREE = 111_320.0

    /** The order the prompt lists parts in: what a route reads first. */
    private val PART_ORDER =
        listOf(
            CatalogPartKind.MAP,
            CatalogPartKind.SEARCH,
            CatalogPartKind.ELEVATION,
            CatalogPartKind.ROUTING,
            CatalogPartKind.CONTOURS,
        )

    /** The missing regions for [stops], or null when the phone has everything or no catalogue is loaded. */
    fun find(
        stops: List<GeoPoint>,
        data: DownloadsData,
    ): MissingRegions? {
        val entries =
            data.catalog
                ?.entries()
                .orEmpty()
                .filter { it.kind == CatalogRegionKind.REGION && it.bounds != null }
        val needed = LinkedHashMap<String, CatalogRegion>()
        for (stop in routePoints(stops)) {
            val candidates = entries.filter { it.bounds?.contains(stop) == true }
            if (candidates.isEmpty() || candidates.any { isRoutable(data, it) }) {
                continue
            }
            val nearest = candidates.minBy { distanceToCentre(it, stop) }
            needed.putIfAbsent(nearest.id, nearest)
        }
        val regions = needed.values.map { entry -> missingOf(data, entry) }
        return if (regions.isEmpty()) null else MissingRegions(regions)
    }

    /** The stops with the samples of each straight segment between them, in route order. */
    private fun routePoints(stops: List<GeoPoint>): List<GeoPoint> =
        stops.take(1) + stops.zipWithNext().flatMap { (from, to) -> samplesBetween(from, to) + to }

    private fun samplesBetween(
        from: GeoPoint,
        to: GeoPoint,
    ): List<GeoPoint> {
        val latitude = to.latitude - from.latitude
        val longitude = (to.longitude - from.longitude) * cos(Math.toRadians((from.latitude + to.latitude) / 2.0))
        val meters = hypot(latitude, longitude) * METERS_PER_DEGREE
        val steps = ceil(meters / SEGMENT_SAMPLE_METERS).toInt()
        return (1 until steps).map { index ->
            val fraction = index.toDouble() / steps
            GeoPoint(
                from.latitude + (to.latitude - from.latitude) * fraction,
                from.longitude + (to.longitude - from.longitude) * fraction,
            )
        }
    }

    private fun isRoutable(
        data: DownloadsData,
        entry: CatalogRegion,
    ): Boolean {
        val installed = data.installed(entry.id) ?: return false
        val needsRouting = entry.parts.any { it.kind == CatalogPartKind.ROUTING }
        return !needsRouting || CatalogPartKind.ROUTING in installed.parts
    }

    private fun missingOf(
        data: DownloadsData,
        entry: CatalogRegion,
    ): MissingRegion {
        val have =
            data
                .installed(entry.id)
                ?.parts
                .orEmpty()
                .toSet()
        val parts =
            entry.parts
                .filter { it.kind in PART_ORDER && it.kind !in have }
                .sortedBy { PART_ORDER.indexOf(it.kind) }
                .map { MissingRegionPart(it.kind, it.bytes) }
        return MissingRegion(entry.id, entry.nameEn, entry.nameUk, parts)
    }

    private fun distanceToCentre(
        entry: CatalogRegion,
        stop: GeoPoint,
    ): Double {
        val box = entry.bounds ?: return Double.MAX_VALUE
        val latitude = (box.southWest.latitude + box.northEast.latitude) / 2.0 - stop.latitude
        val longitude = (box.southWest.longitude + box.northEast.longitude) / 2.0 - stop.longitude
        return latitude * latitude + longitude * longitude
    }
}
