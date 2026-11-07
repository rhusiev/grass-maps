package nl.r1a.grassmap.plugins.info.widgets

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import nl.r1a.grassmap.plugin.api.service.Clock

/**
 * Emits a value only when at least [intervalMillis] passed since the previous
 * emission. The first value of a collection always passes.
 *
 * The operator is leading-edge: a value that arrives inside the window is
 * dropped, not delayed. A widget shows the newest state and the sources it
 * follows (camera, fixes) keep producing, so a dropped value is superseded
 * rather than lost. This keeps the work per source frame to a comparison, and
 * an allocation happens only for an emitted value.
 */
internal fun <T> Flow<T>.throttleLatest(
    intervalMillis: Long,
    clock: Clock,
): Flow<T> =
    flow {
        var lastEmitted: Long? = null
        collect { value ->
            val now = clock.nowMillis()
            val previous = lastEmitted
            val elapsed = if (previous == null) null else now - previous
            if (elapsed == null || elapsed >= intervalMillis || elapsed < 0) {
                lastEmitted = now
                emit(value)
            }
        }
    }
