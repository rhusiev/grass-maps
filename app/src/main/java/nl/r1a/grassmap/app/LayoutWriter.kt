package nl.r1a.grassmaps.app

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.r1a.grassmaps.plugin.api.error.Outcome

/**
 * Serializes layout writes and reads the layout when the lock is held, not when
 * a move requested the write. Two quick moves therefore cannot persist an older
 * order after a newer one: the later write runs last and sees the latest state.
 */
internal class LayoutWriter<T>(
    private val currentLayout: () -> T,
    private val save: suspend (T) -> Outcome<Unit>,
) {
    private val mutex = Mutex()

    suspend fun saveLatest(): Outcome<Unit> = mutex.withLock { save(currentLayout()) }
}
