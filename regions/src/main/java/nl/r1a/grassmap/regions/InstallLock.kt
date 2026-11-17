package nl.r1a.grassmap.regions

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one lock every install into one region store takes. Region installs and
 * raster area installs both sweep the quarantines under `.incoming`, so two
 * of them must never run at once. The app makes one lock and hands it to both.
 */
class InstallLock {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
