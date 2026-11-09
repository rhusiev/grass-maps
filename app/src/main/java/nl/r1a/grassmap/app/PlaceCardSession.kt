package nl.r1a.grassmap.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.r1a.grassmap.plugin.api.model.PlaceCard

/**
 * The card the map screen draws over itself (T-0208).
 *
 * Process-scoped like the map session, so a rebuilt composition keeps the card.
 * The only writer is the debug host command until the search screen (T-0203)
 * opens it from a hit.
 */
object PlaceCardSession {
    private val current = MutableStateFlow<PlaceCard?>(null)

    val card: StateFlow<PlaceCard?> = current.asStateFlow()

    fun show(card: PlaceCard) {
        current.value = card
    }

    fun hide() {
        current.value = null
    }
}
