package nl.r1a.grassmap.plugin.api.testing

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the shared paged-provider contract suite against a fake provider. The
 * suite proves the rules; the fake proves the suite itself exercises them.
 */
class FakeProviderContractTest : PagedProviderContractSuite<String>() {
    override fun createProvider(): PageSource<String> = FakePageSource(items = TEST_ITEMS)

    override fun expectedItems(provider: PageSource<String>): List<String> = TEST_ITEMS

    @Test
    fun fakeProviderMaxPageSizeIsSmallerThanTheDataSet() {
        assertTrue(TEST_ITEMS.size > FakePageSource.DEFAULT_MAX_PAGE_SIZE)
    }

    private companion object {
        val TEST_ITEMS = (1..10).map { "item-$it" }
    }
}
