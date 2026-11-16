package nl.r1a.grassmap.routing

import nl.r1a.grassmap.plugin.api.model.CatalogPartKind
import nl.r1a.grassmap.plugin.api.model.CatalogRegion
import nl.r1a.grassmap.plugin.api.model.CatalogRegionKind
import nl.r1a.grassmap.plugin.api.model.DownloadsData
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.MissingRegion
import nl.r1a.grassmap.plugin.api.model.MissingRegionPart
import nl.r1a.grassmap.plugin.api.model.MissingRegions

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
 * A route that crosses an oblast between its ends is not detected. The stops
 * decide, and the prompt offers what they need.
 */
object MissingRegionFinder {
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
        for (stop in stops) {
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
