package nl.r1a.grassmap.app

import nl.r1a.grassmap.core.ProviderRegistry
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.PlaceCard
import nl.r1a.grassmap.plugin.api.provider.PlaceCardContract
import nl.r1a.grassmap.plugin.api.provider.PlaceCardProvider
import nl.r1a.grassmap.plugin.api.provider.PlaceDetails

private val OSM_PLACES = PluginId("osm.places")
private const val MISSING_CARD_PROVIDER = "places.card"

/**
 * The host side of the place card (T-0208): one place's details through the
 * `places.search` contract, then the `places.card` provider.
 *
 * Details are an injected call rather than a search index handle, so the card
 * reads tags only through the provider seam. A details failure is returned as
 * it is, and the card provider is never asked.
 */
class PlaceCardAccess(
    private val details: suspend (indexName: String, placeId: String, cancellation: CancellationHandle) -> Outcome<PlaceDetails>,
    private val providers: () -> ProviderRegistry,
) {
    suspend fun card(
        indexName: String,
        placeId: String,
        cancellation: CancellationHandle = CancellationHandle.None,
    ): Outcome<PlaceCard> =
        when (val found = details(indexName, placeId, cancellation)) {
            is Outcome.Failure -> {
                found
            }

            is Outcome.Success -> {
                val outcome =
                    providers().invoke(OSM_PLACES, PlaceCardContract.REFERENCE) { provider ->
                        val cardProvider =
                            provider as? PlaceCardProvider
                                ?: return@invoke Outcome.Failure(AppError.Internal(MISSING_CARD_PROVIDER))
                        cardProvider.card(found.value)
                    }
                when (outcome) {
                    is Outcome.Failure -> outcome
                    is Outcome.Success -> outcome.value
                }
            }
        }
}
