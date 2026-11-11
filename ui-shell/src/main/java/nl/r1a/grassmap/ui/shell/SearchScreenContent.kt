package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.PlaceHit
import nl.r1a.grassmap.plugin.api.provider.PlaceSource

/**
 * One result row: the place's name and its address; without an address, the
 * brand or operator it carries; without a brand, its kind (T-0204). [online]
 * marks a row that came from the network (T-0212).
 */
data class SearchResultRow(
    val id: String,
    val name: String,
    val detail: String?,
    val online: Boolean = false,
)

/** One map marker: the same result row's place, at its position. */
data class SearchMarker(
    val id: String,
    val position: GeoPoint,
)

/**
 * The two views of one result set. [rows] fills the list and [markers] draws on
 * the map; both are produced from the same [PlaceHit] list in one pass, so the
 * list and the markers cannot disagree about what a keystroke found.
 */
data class SearchResultView(
    val rows: List<SearchResultRow>,
    val markers: List<SearchMarker>,
)

/**
 * The one derivation from a provider result set to the screen (T-0203).
 *
 * Markers keep the provider order, so marker *n* belongs to row *n*. Styling
 * is a shell decision; nothing here reads a file or an index.
 */
fun searchResultView(hits: List<PlaceHit>): SearchResultView =
    SearchResultView(
        rows =
            hits.map { hit ->
                SearchResultRow(
                    id = hit.id,
                    name = hit.name,
                    detail =
                        hit.address?.takeIf { it.isNotBlank() }
                            ?: hit.brand?.takeIf { it.isNotBlank() }
                            ?: hit.kind.takeIf { it.isNotBlank() },
                    online = hit.source == PlaceSource.ONLINE,
                )
            },
        markers = hits.map { hit -> SearchMarker(id = hit.id, position = hit.position) },
    )

/** The labels the screen draws, already resolved to the active language. */
data class SearchScreenLabels(
    val title: String,
    val hint: String,
    val close: String,
    val noMatches: String,
    val notReady: String,
    val loading: String,
    val onlineOffSendsPosition: String = "",
    val onlineOffNoPosition: String = "",
    val onlineOnSendsPosition: String = "",
    val onlineOnNoPosition: String = "",
    val onlineEnable: String = "",
    val onlineBadge: String = "",
    val positionAllow: String = "",
    val positionStop: String = "",
)

/** The action the online-search notice offers. */
enum class SearchOnlineAction {
    ENABLE,
    ALLOW_POSITION,
    STOP_POSITION,
}

/** The one line the search screen shows about online search and its action. */
data class SearchOnlineNotice(
    val text: String,
    val action: String?,
    val actionKind: SearchOnlineAction,
)

/**
 * What the screen says about online search (T-0212). With the switch off it
 * names exactly what enabling sends: always the query and the IP address, and
 * the map position only when the separate allowance is on. With the switch on
 * it names the source and the current position choice.
 */
fun searchOnlineNotice(
    labels: SearchScreenLabels,
    onlineEnabled: Boolean,
    positionAllowed: Boolean,
): SearchOnlineNotice =
    if (onlineEnabled) {
        SearchOnlineNotice(
            text = if (positionAllowed) labels.onlineOnSendsPosition else labels.onlineOnNoPosition,
            action = if (positionAllowed) labels.positionStop else labels.positionAllow,
            actionKind = if (positionAllowed) SearchOnlineAction.STOP_POSITION else SearchOnlineAction.ALLOW_POSITION,
        )
    } else {
        SearchOnlineNotice(
            text = if (positionAllowed) labels.onlineOffSendsPosition else labels.onlineOffNoPosition,
            action = labels.onlineEnable,
            actionKind = SearchOnlineAction.ENABLE,
        )
    }

/** One keystroke's worth of screen content: the labels, the rows and the state line. */
data class SearchScreenContent(
    val labels: SearchScreenLabels,
    val rows: List<SearchResultRow>,
    val markers: List<SearchMarker>,
    val message: String?,
    val loading: Boolean,
    val online: SearchOnlineNotice? = null,
)

/**
 * Turns the newest result set and the session flags into what the screen draws.
 * A blank field draws nothing below it; a failure says the index is missing
 * instead of pretending there are no matches.
 */
fun searchScreenContent(
    hits: List<PlaceHit>,
    query: String,
    loading: Boolean,
    failed: Boolean,
    labels: SearchScreenLabels,
    online: SearchOnlineNotice? = null,
): SearchScreenContent {
    val view = searchResultView(hits)
    val message =
        when {
            failed -> labels.notReady
            loading && view.rows.isEmpty() -> null
            query.isBlank() -> null
            view.rows.isEmpty() -> labels.noMatches
            else -> null
        }
    return SearchScreenContent(
        labels = labels,
        rows = view.rows,
        markers = view.markers,
        message = message,
        loading = loading,
        online = online,
    )
}
