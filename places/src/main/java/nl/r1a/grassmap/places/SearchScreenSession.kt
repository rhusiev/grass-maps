package nl.r1a.grassmap.places

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.provider.PlaceHit
import nl.r1a.grassmap.plugin.api.provider.SearchContext
import java.util.concurrent.atomic.AtomicBoolean

/** What the search screen shows below the field. */
enum class SearchScreenStatus {
    /** The field is empty, so there is nothing to search for. */
    IDLE,

    /** A keystroke is running and no result is drawn yet. */
    LOADING,

    /** The newest keystroke produced at least one hit. */
    RESULTS,

    /** The newest keystroke found nothing. */
    EMPTY,

    /** The newest keystroke failed; the reason is in [SearchScreenState.error]. */
    FAILED,
}

/**
 * One search screen state. [query] is the text the user typed, untrimmed, and
 * [sequence] is the keystroke order the owner assigned.
 */
data class SearchScreenState(
    val query: String = "",
    val sequence: Long = 0,
    val status: SearchScreenStatus = SearchScreenStatus.IDLE,
    val hits: List<PlaceHit> = emptyList(),
    val error: AppError? = null,
)

/** One search request: everything a provider needs that the field does not hold. */
data class SearchScreenRequest(
    val query: String,
    val index: String,
    val limit: Int,
    val context: SearchContext,
    val cancellation: CancellationHandle,
    /**
     * Publishes the rows a provider already has while the rest of the request
     * is still running (T-0212). The session applies its sequence guard, so a
     * partial result from a superseded keystroke is dropped like a final one.
     */
    val onPartial: suspend (List<PlaceHit>) -> Unit = {},
)

/**
 * The state behind the T-0203 search screen: one field, and the result set the
 * newest keystroke produced.
 *
 * The shell never searches. It sends a keystroke here through the command bus
 * and draws [state]; the map markers and the result list are two views of the
 * same [SearchScreenState.hits], so they cannot disagree.
 *
 * Ordering has two halves, following the favourites screen (T-0214). The
 * command is registered with a cancellation key, so a newer keystroke stops the
 * older command through its coroutine job. Independently of that, this session
 * stamps each keystroke with [sequence] and publishes only the newest one
 * started. The second half covers the race where both keystrokes reach the
 * session before either finishes, and any caller that bypasses the bus - the
 * replay harness does.
 *
 * A superseded or cancelled keystroke returns [AppError.Cancelled] and publishes
 * nothing, so a slow older result can never replace a newer one.
 */
class SearchScreenSession(
    private val defaultLimit: Int = DEFAULT_LIMIT,
    private val search: suspend (SearchScreenRequest) -> Outcome<List<PlaceHit>>,
) {
    private val mutex = Mutex()
    private val current = MutableStateFlow(SearchScreenState())
    private var newestStarted: Long = 0
    private var newestPublished: Long = 0
    private var inFlightToken: AtomicBoolean? = null

    val state: StateFlow<SearchScreenState> = current.asStateFlow()

    /**
     * Runs one keystroke. [sequence] orders keystrokes; 0 means "no ordering",
     * which only a one-off caller passes.
     *
     * A blank query publishes [SearchScreenStatus.IDLE] with no hits and does
     * not reach the provider.
     */
    suspend fun submit(
        query: String,
        sequence: Long = 0,
        index: String = DEFAULT_INDEX,
        limit: Int = defaultLimit,
        context: SearchContext = SearchContext(),
    ): Outcome<SearchScreenState> {
        val text = query.trim()
        if (limit !in 1..MAX_SEARCH_RESULTS) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val token = AtomicBoolean(false)
        mutex.withLock {
            if (sequence in 1..newestStarted) {
                return Outcome.Failure(AppError.Cancelled)
            }
            if (sequence > newestStarted) {
                newestStarted = sequence
            }
            // The older call sees this flag through the handle it already holds,
            // which is how the cancellation crosses the Rust boundary.
            inFlightToken?.set(true)
            inFlightToken = token
        }
        if (text.isEmpty()) {
            publishIfNewest(SearchScreenState(query = query, sequence = sequence, status = SearchScreenStatus.IDLE))
            return Outcome.Success(current.value)
        }
        val loading =
            publishIfNewest(
                SearchScreenState(
                    query = query,
                    sequence = sequence,
                    status = SearchScreenStatus.LOADING,
                    hits = current.value.hits,
                ),
            )
        if (!loading) {
            return Outcome.Failure(AppError.Cancelled)
        }
        val outcome =
            search(
                SearchScreenRequest(
                    query = text,
                    index = index,
                    limit = limit,
                    context = context,
                    cancellation = CancellationHandle { token.get() },
                    onPartial = { hits -> publishPartial(query, sequence, hits, token) },
                ),
            )
        return when (outcome) {
            is Outcome.Failure -> {
                if (outcome.error == AppError.Cancelled || token.get()) {
                    Outcome.Failure(AppError.Cancelled)
                } else {
                    publishIfNewest(
                        SearchScreenState(
                            query = query,
                            sequence = sequence,
                            status = SearchScreenStatus.FAILED,
                            error = outcome.error,
                        ),
                        terminal = true,
                    )
                    Outcome.Failure(outcome.error)
                }
            }

            is Outcome.Success -> {
                if (token.get()) {
                    return Outcome.Failure(AppError.Cancelled)
                }
                val hits = outcome.value
                val published =
                    publishIfNewest(
                        SearchScreenState(
                            query = query,
                            sequence = sequence,
                            status = if (hits.isEmpty()) SearchScreenStatus.EMPTY else SearchScreenStatus.RESULTS,
                            hits = hits,
                        ),
                        terminal = true,
                    )
                if (published) Outcome.Success(current.value) else Outcome.Failure(AppError.Cancelled)
            }
        }
    }

    /** Clears the field and its results without reaching the provider. */
    suspend fun clear(): SearchScreenState {
        mutex.withLock {
            inFlightToken?.set(true)
            inFlightToken = null
            newestStarted = 0
            newestPublished = 0
            current.value = SearchScreenState()
        }
        return current.value
    }

    /**
     * Publishes rows a provider already has without ending the keystroke
     * (T-0212). An empty partial keeps [SearchScreenStatus.LOADING], so the
     * screen says "searching" rather than "no matches" while the slower half
     * is still out.
     */
    private suspend fun publishPartial(
        query: String,
        sequence: Long,
        hits: List<PlaceHit>,
        token: AtomicBoolean,
    ) {
        mutex.withLock {
            if (token.get()) {
                return@withLock
            }
            if (sequence > 0 && (sequence < newestStarted || sequence <= newestPublished)) {
                return@withLock
            }
            current.value =
                SearchScreenState(
                    query = query,
                    sequence = sequence,
                    status = if (hits.isEmpty()) SearchScreenStatus.LOADING else SearchScreenStatus.RESULTS,
                    hits = hits,
                )
        }
    }

    /**
     * Publishes [next] only while it is still the newest keystroke started. A
     * result that lost that race is discarded and its caller sees
     * [AppError.Cancelled].
     *
     * Only a terminal state (results, empty, failed) consumes its sequence, so
     * the intermediate loading state does not hide the result that follows it.
     */
    private suspend fun publishIfNewest(
        next: SearchScreenState,
        terminal: Boolean = false,
    ): Boolean =
        mutex.withLock {
            if (next.sequence > 0 && (next.sequence < newestStarted || next.sequence <= newestPublished)) {
                return@withLock false
            }
            if (terminal && next.sequence > newestPublished) {
                newestPublished = next.sequence
            }
            current.value = next
            true
        }

    companion object {
        /** The index the debug commands use until the region picker exists. */
        const val DEFAULT_INDEX: String = "lviv"

        /** Enough rows for the sheet and the markers the map draws. */
        const val DEFAULT_LIMIT: Int = 5
    }
}
