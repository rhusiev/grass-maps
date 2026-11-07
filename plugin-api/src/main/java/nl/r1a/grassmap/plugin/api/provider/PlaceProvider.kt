package nl.r1a.grassmap.plugin.api.provider

import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint

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

    /** The full record behind a [PlaceHit.id] this provider issued. */
    suspend fun details(
        placeId: String,
        cancellation: CancellationHandle,
    ): Outcome<Place>
}

object PlaceProviderContract {
    const val NAME: String = "places.search"
    const val VERSION: String = "1.0.0"

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
 */
data class PlaceHit(
    val id: String,
    val name: String,
    val kind: String,
    val position: GeoPoint,
    val address: String?,
    val regionBuild: String,
    val score: Float,
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
