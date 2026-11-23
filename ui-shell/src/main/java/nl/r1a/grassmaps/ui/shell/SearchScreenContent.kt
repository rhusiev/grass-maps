package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.provider.PlaceHit
import nl.r1a.grassmaps.plugin.api.provider.PlaceSource

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
    val notReadyAction: String = "",
    val loading: String,
    val onlineOffSendsPosition: String = "",
    val onlineOffNoPosition: String = "",
    val onlineOnSendsPosition: String = "",
    val onlineOnNoPosition: String = "",
    val onlineEnable: String = "",
    val onlineDisable: String = "",
    val onlineBadge: String = "",
    val onlineStopped: String = "",
    val onlineStoppedWithOnline: String = "",
    val onlineStoppedNoResults: String = "",
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
    val offAction: String? = null,
)

/**
 * What the screen says about online search (T-0212). With the switch off it
 * names exactly what enabling sends: always the query and the IP address, and
 * the map position only when the separate allowance is on. With the switch on
 * it names the source and the current position choice, and also offers to turn
 * the online feature off. The one-time prompt still only offers to enable it.
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
            offAction = labels.onlineDisable,
        )
    } else {
        SearchOnlineNotice(
            text = if (positionAllowed) labels.onlineOffSendsPosition else labels.onlineOffNoPosition,
            action = labels.onlineEnable,
            actionKind = SearchOnlineAction.ENABLE,
        )
    }

/** The action the no-index line offers: the one entry point to map downloads. */
enum class SearchNotReadyAction {
    OPEN_DOWNLOADS,
}

/**
 * What a fresh install sees when no offline index is mounted: why the search
 * needs a map region, and the one action that takes the user to Downloads.
 */
data class SearchNotReadyNotice(
    val explanation: String,
    val action: String,
    val actionKind: SearchNotReadyAction,
)

/** The no-index line and its action, both from the screen's bundles. */
fun searchNotReadyNotice(labels: SearchScreenLabels): SearchNotReadyNotice =
    SearchNotReadyNotice(
        explanation = labels.notReady,
        action = labels.notReadyAction,
        actionKind = SearchNotReadyAction.OPEN_DOWNLOADS,
    )

/**
 * Sends the no-index action to the callback that opens Downloads. Kept pure so
 * a JVM test pins which callback the offered action calls.
 */
fun runNotReadyAction(
    action: SearchNotReadyAction,
    onOpenDownloads: () -> Unit,
) {
    when (action) {
        SearchNotReadyAction.OPEN_DOWNLOADS -> onOpenDownloads()
    }
}

/** One keystroke's worth of screen content: the labels, the rows and the state line. */
data class SearchScreenContent(
    val labels: SearchScreenLabels,
    val rows: List<SearchResultRow>,
    val markers: List<SearchMarker>,
    val message: String?,
    val loading: Boolean,
    val online: SearchOnlineNotice? = null,
    val notReady: SearchNotReadyNotice? = null,
)

/**
 * Turns the newest result set and the session flags into what the screen draws.
 * A blank field draws nothing below it; a failure explains the missing index
 * and offers Downloads instead of pretending there are no matches.
 *
 * A switch that stopped the online half adds a line about it, but never changes
 * a row's source: [SearchResultRow.online] remains the only mark of an online
 * hit, so an offline row is never presented as online (T-0616).
 */
fun searchScreenContent(
    hits: List<PlaceHit>,
    query: String,
    loading: Boolean,
    failed: Boolean,
    labels: SearchScreenLabels,
    online: SearchOnlineNotice? = null,
    onlineStopped: Boolean = false,
): SearchScreenContent {
    val view = searchResultView(hits)
    val message =
        when {
            failed -> null
            onlineStopped && view.rows.isEmpty() -> labels.onlineStoppedNoResults
            onlineStopped && view.rows.any { it.online } -> labels.onlineStoppedWithOnline
            onlineStopped -> labels.onlineStopped
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
        notReady = if (failed) searchNotReadyNotice(labels) else null,
    )
}
