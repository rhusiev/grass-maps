package nl.r1a.grassmap.plugin.api.testing

import kotlinx.coroutines.yield
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.contract.Page
import nl.r1a.grassmap.plugin.api.contract.PageCursor
import nl.r1a.grassmap.plugin.api.contract.PageRequest
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.provider.ProviderContract

/**
 * An in-memory [PageSource] used to exercise the contract suite. It checks
 * cancellation between items and yields there, so concurrent traversals
 * interleave the way a real provider's do.
 */
internal class FakePageSource<T : Any>(
    private val items: List<T>,
    override val maxPageSize: Int = DEFAULT_MAX_PAGE_SIZE,
) : PageSource<T> {
    override val contract: ProviderContract = ProviderContract("test.paged", "1.0.0")

    override suspend fun page(
        request: PageRequest,
        cancellation: CancellationHandle,
    ): Outcome<Page<T>> {
        if (cancellation.isCancelled()) {
            return Outcome.Failure(AppError.Cancelled)
        }
        if (request.limit !in 1..maxPageSize) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val start = offsetOf(request.cursor) ?: return Outcome.Failure(AppError.InvalidInput)
        val end = minOf(start + request.limit, items.size)
        repeat(end - start) {
            if (cancellation.isCancelled()) {
                return Outcome.Failure(AppError.Cancelled)
            }
            yield()
        }
        val next = if (end < items.size) PageCursor(end.toString()) else null
        return Outcome.Success(Page(items.subList(start, end).toList(), next))
    }

    private fun offsetOf(cursor: PageCursor?): Int? {
        if (cursor == null) {
            return 0
        }
        val offset = cursor.token.toIntOrNull() ?: return null
        return offset.takeIf { it in 0 until items.size }
    }

    companion object {
        const val DEFAULT_MAX_PAGE_SIZE = 8
    }
}
