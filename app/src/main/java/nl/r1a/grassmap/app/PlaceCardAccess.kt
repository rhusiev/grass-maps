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
import nl.r1a.grassmap.plugins.share.ShareCard

private val OSM_PLACES = PluginId("osm.places")
private const val MISSING_CARD_PROVIDER = "places.card"

/**
 * The host side of the place card (T-0208): one place's details through the
 * `places.search` contract, then the `places.card` provider.
 *
 * Details are an injected call rather than a search index handle, so the card
 * reads tags only through the provider seam. A details failure is returned as
 * it is, and the card provider is never asked. The provider maps tags; the
 * coordinate rows and the share, copy and open-in buttons need the point and
 * the identity the same details carry, so [ShareCard] adds them here (T-0210).
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
                    is Outcome.Failure -> {
                        outcome
                    }

                    is Outcome.Success -> {
                        when (val card = outcome.value) {
                            is Outcome.Failure -> card
                            is Outcome.Success -> Outcome.Success(ShareCard.decorate(card.value, found.value))
                        }
                    }
                }
            }
        }
}

/**
 * Resolves the card for one index place id and shows it over the map. The search
 * screen (T-0203) and the debug `core.place.card_show` command share this path,
 * so a card opened from a result and a card opened from the control channel are
 * the same card.
 */
internal suspend fun AppContainer.showPlaceCard(
    index: String,
    placeId: String,
): Outcome<PlaceCard> {
    val access =
        PlaceCardAccess(
            details = { indexName, id, cancellation -> places.details(indexName, id, cancellation) },
            providers = { providerRegistry },
        )
    return when (val outcome = access.card(index, placeId, CancellationHandle.None)) {
        is Outcome.Failure -> {
            outcome
        }

        is Outcome.Success -> {
            PlaceCardSession.show(outcome.value)
            outcome
        }
    }
}
