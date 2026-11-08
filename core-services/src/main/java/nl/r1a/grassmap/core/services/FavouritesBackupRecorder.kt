package nl.r1a.grassmap.core.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * Turns favourites mutations into at most one backup per quiet window (T-0216).
 *
 * Every call to [onMutation] restarts the timer, so a burst of edits writes one
 * copy after the last edit instead of one copy per edit. The timer runs on
 * coroutine time from [scope], while the calendar rule in [FavouritesBackupStore]
 * uses the store's injected wall clock; a test can drive both independently.
 *
 * The trigger is called only after a mutation succeeded. A failed export or
 * capture is reported to [onResult] and dropped: the previous copies stay
 * intact and the next mutation tries again.
 */
class FavouritesBackupRecorder(
    private val store: FavouritesBackupStore,
    private val scope: CoroutineScope,
    private val export: suspend () -> Outcome<ByteArray>,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val onResult: (Outcome<FavouritesBackupCopy>) -> Unit = {},
) {
    private val lock = Any()
    private var pending: Job? = null

    init {
        require(debounceMillis > 0) { "debounceMillis must be positive" }
    }

    /** Schedules one copy after [debounceMillis] without a further mutation. */
    fun onMutation() {
        synchronized(lock) {
            pending?.cancel()
            pending =
                scope.launch {
                    delay(debounceMillis)
                    val result =
                        when (val document = export()) {
                            is Outcome.Success -> store.capture(document.value)
                            is Outcome.Failure -> document
                        }
                    onResult(result)
                }
        }
    }

    companion object {
        /** The default quiet window before a backup is written. */
        const val DEFAULT_DEBOUNCE_MILLIS = 10_000L
    }
}
