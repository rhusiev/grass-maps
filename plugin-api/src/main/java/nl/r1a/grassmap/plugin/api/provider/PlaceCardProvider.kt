package nl.r1a.grassmap.plugin.api.provider

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.PlaceCard

/**
 * The `places.card` contract: maps one place's already-loaded details onto the
 * rows a place card shows.
 *
 * The provider is given [PlaceDetails] and nothing else. It never opens an
 * index, reads a file or asks a network, so the OSM tags on a card can only be
 * the tags the `places.search` provider returned for that place. A build that
 * wants a different field set adds a provider; the shell keeps its layout.
 */
interface PlaceCardProvider : Provider {
    /**
     * Builds the card for [details]. A details value with no tags is a valid
     * empty card, not an error; a tag the mapping does not show is omitted.
     */
    fun card(details: PlaceDetails): Outcome<PlaceCard>
}

object PlaceCardContract {
    const val NAME: String = "places.card"
    const val VERSION: String = "1.1.0"

    val REFERENCE: ProviderContract = ProviderContract(NAME, VERSION)
}
