package nl.r1a.grassmap.places

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.PlaceHit
import nl.r1a.grassmap.plugin.api.provider.SearchContext

/**
 * One offline query over every mounted pack that has a search section (T-0633).
 *
 * The sections run concurrently, so the answer waits for the slowest pack, not
 * for the sum of them. Rows from every successful section are merged: a row
 * repeats another when it has the same id, or the same normalized name within
 * [REGION_DUPLICATE_RADIUS_METERS]. The merged rows are ranked by the provider
 * score (higher first; every offline pack uses the same reader, so the scale is
 * shared), then by distance from the current fix, or from the viewport center
 * when there is no fix, then by id for a stable order. Duplicates and the cap
 * are applied after ranking, so the best copy wins and its
 * [PlaceHit.regionBuild] stays the section that opens its card.
 *
 * A failed section does not hide the others; when every section fails the first
 * failure is returned. A cancelled query returns [AppError.Cancelled] without
 * merging partial rows. A section named twice is queried once. An empty list is
 * [AppError.NotReady]; a list holding only a blank name reaches the section
 * handler once, because that is the app's no-mounted-section fallback and a
 * typed coordinate still answers there.
 */
class MultiRegionSearch(
    /** Runs one section, usually [PlacesAccess.search]; a section may be opened lazily here. */
    private val searchSection: suspend (
        section: String,
        query: String,
        context: SearchContext,
        limit: Int,
        cancellation: CancellationHandle,
    ) -> Outcome<List<PlaceHit>>,
) {
    suspend fun search(
        sections: List<String>,
        query: String,
        context: SearchContext,
        limit: Int,
        cancellation: CancellationHandle,
    ): Outcome<List<PlaceHit>> {
        if (limit !in 1..MAX_SEARCH_RESULTS) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val names =
            sections
                .filter { section -> section.isNotEmpty() }
                .distinct()
                .ifEmpty { sections.distinct().take(1) }
        if (names.isEmpty()) {
            return Outcome.Failure(AppError.NotReady)
        }
        if (cancellation.isCancelled()) {
            return Outcome.Failure(AppError.Cancelled)
        }
        val answered =
            coroutineScope {
                names
                    .map { name ->
                        async { name to searchSection(name, query, context, limit, cancellation) }
                    }.awaitAll()
            }
        if (cancellation.isCancelled() || answered.any { (_, outcome) -> outcome.isCancelled() }) {
            return Outcome.Failure(AppError.Cancelled)
        }
        val successes = answered.mapNotNull { (_, outcome) -> (outcome as? Outcome.Success)?.value }
        if (successes.isEmpty()) {
            return answered.first().second
        }
        return Outcome.Success(mergeHits(successes.flatten(), context, limit))
    }

    companion object {
        /**
         * How far apart two pack rows named alike may sit and still be one
         * place. The same OSM row lands at the same point in every pack; the
         * radius only absorbs coordinate rounding, and stays below a city
         * block so two same-name neighbours remain two rows. The offline/online
         * merge uses its own larger radius, because a Photon centroid can sit
         * tens of metres from the OSM node.
         */
        const val REGION_DUPLICATE_RADIUS_METERS: Double = 25.0

        /** The point the distance tie-break measures from. */
        internal fun originOf(context: SearchContext): GeoPoint? =
            context.position
                ?: context.viewport?.let { box ->
                    GeoPoint(
                        latitude = (box.southWest.latitude + box.northEast.latitude) / 2.0,
                        longitude = (box.southWest.longitude + box.northEast.longitude) / 2.0,
                    )
                }

        /** True when [candidate] repeats [existing] by id or by name and proximity. */
        internal fun isSamePlace(
            existing: PlaceHit,
            candidate: PlaceHit,
        ): Boolean {
            if (existing.id == candidate.id) {
                return true
            }
            val name = PlaceMatching.normalizedName(existing.name)
            if (name.isEmpty() || name != PlaceMatching.normalizedName(candidate.name)) {
                return false
            }
            return PlaceMatching.distanceMeters(existing.position, candidate.position) <= REGION_DUPLICATE_RADIUS_METERS
        }

        /** Rank first, then drop repeats, then cap, so the best copy and its region survive. */
        internal fun mergeHits(
            hits: List<PlaceHit>,
            context: SearchContext,
            limit: Int,
        ): List<PlaceHit> {
            val origin = originOf(context)
            val ranked =
                hits.sortedWith(
                    compareByDescending<PlaceHit> { hit -> hit.score }
                        .thenBy { hit -> origin?.let { point -> PlaceMatching.distanceMeters(point, hit.position) } ?: 0.0 }
                        .thenBy { hit -> hit.id },
                )
            val accepted = ArrayList<PlaceHit>(limit)
            for (candidate in ranked) {
                if (accepted.size == limit) {
                    break
                }
                if (accepted.none { existing -> isSamePlace(existing, candidate) }) {
                    accepted += candidate
                }
            }
            return accepted
        }
    }
}

/** True when a section stopped because the caller's query was superseded. */
private fun Outcome<*>.isCancelled(): Boolean = this is Outcome.Failure && error == AppError.Cancelled
