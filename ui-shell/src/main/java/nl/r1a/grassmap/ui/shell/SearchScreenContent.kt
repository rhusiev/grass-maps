package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.PlaceHit

/**
 * One result row: the place's name and its address; without an address, the
 * brand or operator it carries; without a brand, its kind (T-0204).
 */
data class SearchResultRow(
    val id: String,
    val name: String,
    val detail: String?,
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
)

/** One keystroke's worth of screen content: the labels, the rows and the state line. */
data class SearchScreenContent(
    val labels: SearchScreenLabels,
    val rows: List<SearchResultRow>,
    val markers: List<SearchMarker>,
    val message: String?,
    val loading: Boolean,
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
    )
}
