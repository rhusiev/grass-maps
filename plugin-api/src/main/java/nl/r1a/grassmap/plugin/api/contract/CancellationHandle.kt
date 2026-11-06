package nl.r1a.grassmap.plugin.api.contract

/**
 * Cooperative cancellation for one long-running provider call.
 *
 * A Kotlin provider already sees cancellation through its coroutine
 * [kotlinx.coroutines.Job]. This handle carries the same signal across the Rust
 * boundary, which has no coroutine context. The handle is not a thread-wide
 * flag: it belongs to one call. A provider checks it before starting work and
 * between chunks, and returns
 * [nl.r1a.grassmap.plugin.api.error.AppError.Cancelled] once it is set; a call
 * that arrives already cancelled does no work at all.
 */
fun interface CancellationHandle {
    fun isCancelled(): Boolean

    companion object {
        /** A handle that never cancels, for calls no owner can cancel. */
        val None: CancellationHandle = CancellationHandle { false }
    }
}
