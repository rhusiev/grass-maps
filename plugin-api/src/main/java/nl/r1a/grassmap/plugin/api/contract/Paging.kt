package nl.r1a.grassmap.plugin.api.contract

/**
 * An opaque continuation token returned by a paged query.
 *
 * A caller passes the token back unchanged and never parses it: only the
 * provider that issued it knows what it means. A token is valid only for the
 * query owner that received it; an unknown or stale token is answered with
 * [nl.r1a.grassmap.plugin.api.error.AppError.InvalidInput].
 */
@JvmInline
value class PageCursor(
    val token: String,
)

/**
 * One page request for a paged query.
 *
 * [limit] must be at least 1 and no more than the provider's declared maximum.
 * A provider answers a request outside that range with
 * [nl.r1a.grassmap.plugin.api.error.AppError.InvalidInput] instead of clamping
 * it, so a caller cannot silently receive less work than it asked for. A null
 * [cursor] starts the query from the first page.
 */
data class PageRequest(
    val limit: Int,
    val cursor: PageCursor? = null,
)

/**
 * One page of a paged query.
 *
 * [nextCursor] is null on the last page. A page with no items is the last page
 * and must carry a null cursor, so a caller always makes progress. Pages are
 * ordered by the provider's own stable order and a traversal returns every item
 * exactly once.
 */
data class Page<T>(
    val items: List<T>,
    val nextCursor: PageCursor?,
)
