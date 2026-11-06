package nl.r1a.grassmap.plugin.api.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.contract.Page
import nl.r1a.grassmap.plugin.api.contract.PageCursor
import nl.r1a.grassmap.plugin.api.contract.PageRequest
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The shared contract suite for paged providers.
 *
 * A provider test creates a fresh provider with a known data set, supplies the
 * expected content order and the suite proves the paging rules: limits, cursor
 * traversal, ordering, concurrency and cancellation. The suite never touches
 * production data; it runs against whatever [PageSource] the test supplies.
 */
abstract class PagedProviderContractSuite<T : Any> {
    protected abstract fun createProvider(): PageSource<T>

    protected abstract fun expectedItems(provider: PageSource<T>): List<T>

    @Test
    fun traversalVisitsEveryItemOnceInOrder() =
        runTest {
            val provider = createProvider()
            val expected = expectedItems(provider)

            for (limit in pageLimits(provider)) {
                assertEquals("limit=$limit", expected, collect(provider, limit))
            }
        }

    @Test
    fun everyPageRespectsTheRequestedLimit() =
        runTest {
            val provider = createProvider()

            for (limit in pageLimits(provider)) {
                var cursor: PageCursor? = null
                var pages = 0
                do {
                    val page = success(provider.page(PageRequest(limit, cursor), CancellationHandle.None))
                    assertTrue("page of ${page.items.size} for limit=$limit", page.items.size <= limit)
                    assertTrue("empty page with a cursor", page.items.isNotEmpty() || page.nextCursor == null)
                    cursor = page.nextCursor
                    pages += 1
                    assertTrue("paging did not terminate after $pages pages", pages <= MAX_PAGES)
                } while (cursor != null)
            }
        }

    @Test
    fun orderIsStableAcrossTraversals() =
        runTest {
            val provider = createProvider()
            val limits = pageLimits(provider)
            val first = collect(provider, limits.first())
            val second = collect(provider, limits.last())

            assertEquals(expectedItems(provider), first)
            assertEquals(first, second)
        }

    @Test
    fun limitAboveProviderMaximumIsRejected() =
        runTest {
            val provider = createProvider()

            assertInvalidInput(provider.page(PageRequest(provider.maxPageSize + 1), CancellationHandle.None))
        }

    @Test
    fun limitBelowOneIsRejected() =
        runTest {
            val provider = createProvider()

            assertInvalidInput(provider.page(PageRequest(0), CancellationHandle.None))
            assertInvalidInput(provider.page(PageRequest(-1), CancellationHandle.None))
        }

    @Test
    fun unknownCursorIsRejected() =
        runTest {
            val provider = createProvider()

            val unknown = PageCursor("cursor-from-another-query")
            assertInvalidInput(provider.page(PageRequest(1, unknown), CancellationHandle.None))
        }

    @Test
    fun concurrentTraversalsAreIndependent() =
        runTest {
            val provider = createProvider()
            val expected = expectedItems(provider)
            val limits = pageLimits(provider)

            val results =
                withContext(Dispatchers.Default) {
                    coroutineScope {
                        limits
                            .mapIndexed { index, limit ->
                                async { collect(provider, limit) to index }
                            }.awaitAll()
                    }
                }

            for ((items, index) in results) {
                assertEquals("traversal $index with limit=${limits[index]}", expected, items)
            }
        }

    @Test
    fun concurrentPagesOfOneCursorAgree() =
        runTest {
            val provider = createProvider()
            val request = PageRequest(pageLimits(provider).last())

            val pages =
                coroutineScope {
                    (1..CONCURRENT_CALLS)
                        .map {
                            async { success(provider.page(request, CancellationHandle.None)) }
                        }.awaitAll()
                }

            val first = pages.first()
            for (page in pages) {
                assertEquals(first, page)
            }
        }

    @Test
    fun preCancelledRequestIsRefused() =
        runTest {
            val provider = createProvider()
            val cancelled = CancellationHandle { true }

            assertEquals(Outcome.Failure(AppError.Cancelled), provider.page(PageRequest(1), cancelled))
        }

    @Test
    fun cancellationAfterTheFirstPageNeverReturnsLaterItems() =
        runTest {
            val provider = createProvider()
            assumeTrue(expectedItems(provider).size > 1)

            val first = success(provider.page(PageRequest(1), CancellationHandle.None))
            val next = first.nextCursor ?: return@runTest
            val cancelled = CancellationHandle { true }

            assertEquals(Outcome.Failure(AppError.Cancelled), provider.page(PageRequest(1, next), cancelled))
        }

    @Test
    fun providerStaysUsableAfterACancelledPage() =
        runTest {
            val provider = createProvider()
            val expected = expectedItems(provider)

            provider.page(PageRequest(1), CancellationHandle { true })

            assertEquals(expected, collect(provider, pageLimits(provider).first()))
        }

    private fun pageLimits(provider: PageSource<T>): List<Int> {
        require(provider.maxPageSize >= 1) { "maxPageSize must be at least 1" }
        return listOf(1, 2, 3, provider.maxPageSize)
            .filter { it <= provider.maxPageSize }
            .distinct()
    }

    private suspend fun collect(
        provider: PageSource<T>,
        limit: Int,
    ): List<T> {
        val items = mutableListOf<T>()
        var cursor: PageCursor? = null
        var pages = 0
        do {
            val page = success(provider.page(PageRequest(limit, cursor), CancellationHandle.None))
            items += page.items
            cursor = page.nextCursor
            pages += 1
            assertTrue("paging did not terminate after $pages pages", pages <= MAX_PAGES)
        } while (cursor != null)
        return items
    }

    private fun success(outcome: Outcome<Page<T>>): Page<T> =
        when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("expected a page, got ${outcome.error}")
        }

    private fun assertInvalidInput(outcome: Outcome<Page<T>>) {
        assertEquals(Outcome.Failure(AppError.InvalidInput), outcome)
    }

    private companion object {
        const val CONCURRENT_CALLS = 8
        const val MAX_PAGES = 10_000
    }
}
