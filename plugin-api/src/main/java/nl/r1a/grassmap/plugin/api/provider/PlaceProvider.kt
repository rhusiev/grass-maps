package nl.r1a.grassmap.plugin.api.provider

import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.OsmType

/**
 * Offline and online place search behind the `places.search` contract.
 *
 * Every call is one-shot and cancellable. A provider owns its index; the host
 * fans several providers out, merges and ranks, and keeps the progressive UI in
 * its own state. A query that a newer keystroke superseded returns
 * [AppError.Cancelled], not a partial list.
 */
interface PlaceProvider : Provider {
    /**
     * Places matching [query]. [context.position] is present only when the
     * caller allows the provider to use it; a provider that gets it may change
     * ranking, and must not send it anywhere without the switch that owns it.
     */
    suspend fun search(
        query: String,
        context: SearchContext,
        limit: Int,
        cancellation: CancellationHandle,
    ): Outcome<List<PlaceHit>>

    /**
     * Places inside [bbox] and, when [categories] is not empty, of one of those
     * exact kinds ("amenity=cafe"). The result is ordered by the provider.
     */
    suspend fun pois(
        bbox: BoundingBox,
        categories: Set<String>,
        limit: Int,
        cancellation: CancellationHandle,
    ): Outcome<List<Place>>

    /**
     * The full record behind a [PlaceHit.id] this provider issued, with the
     * canonical OpenStreetMap identity and tag map from the region's features
     * component.
     *
     * A place the component does not carry still returns its [PlaceDetails],
     * with a null identity and an empty tag map. A missing, truncated or
     * unknown-version component is a typed failure, never an empty map.
     */
    suspend fun details(
        placeId: String,
        cancellation: CancellationHandle,
    ): Outcome<PlaceDetails>
}

object PlaceProviderContract {
    const val NAME: String = "places.search"
    const val VERSION: String = "2.0.0"

    val REFERENCE: ProviderContract = ProviderContract(NAME, VERSION)
}

/**
 * What the caller lets the provider know. [viewport] biases ranking towards the
 * visible area, [language] selects name and address wording, and [position] is
 * null whenever the caller does not allow location use.
 */
data class SearchContext(
    val viewport: BoundingBox? = null,
    val language: String? = null,
    val position: GeoPoint? = null,
)

/**
 * One ranked result. [id] is stable for the same index build and is the value
 * [PlaceProvider.details] accepts. [regionBuild] names the build the row came
 * from, so a stale hit is never applied to a fresh region. [score] is the
 * provider's own ordering value and is not comparable across providers.
 *
 * [kind] stays the exact source category ("shop=supermarket"), which category
 * filters and probes match on; [brand] is the brand or operator the place
 * carries, null when it has none. The shell shows [brand] in preference to
 * [kind] when the place has no address, so a branch reads its brand rather
 * than "shop=supermarket".
 */
data class PlaceHit(
    val id: String,
    val name: String,
    val kind: String,
    val position: GeoPoint,
    val address: String?,
    val regionBuild: String,
    val score: Float,
    val brand: String? = null,
)

/**
 * A place record. [names] keeps the other names the index knows, keyed by
 * language tag where the source states one.
 */
data class Place(
    val id: String,
    val name: String,
    val kind: String,
    val position: GeoPoint,
    val address: String?,
    val regionBuild: String,
    val names: Map<String, String> = emptyMap(),
)

/**
 * The full record behind a [PlaceHit.id]: the place itself, its canonical
 * OpenStreetMap identity and its raw OSM tag map.
 *
 * [osmType] and [osmId] are both null when the region build has no feature
 * entry for the place. [tags] is empty when the place carries no tags; it is
 * never null, and its absence is not an error.
 */
data class PlaceDetails(
    val place: Place,
    val osmType: OsmType?,
    val osmId: Long?,
    val tags: Map<String, String>,
)
