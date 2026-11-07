package nl.r1a.grassmap.plugins.synthetic.data

import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.contract.Page
import nl.r1a.grassmap.plugin.api.contract.PageRequest
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.error.map
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.provider.AttributeRequest
import nl.r1a.grassmap.plugin.api.provider.DataSource
import nl.r1a.grassmap.plugin.api.provider.ProviderContract
import nl.r1a.grassmap.plugin.api.provider.WayAttribute
import nl.r1a.grassmap.plugin.api.testing.PageSource
import nl.r1a.grassmap.plugin.api.testing.PagedProviderContractSuite

/**
 * Runs the shared T-0015 paging suite against the synthetic source. The suite
 * sees the DataSource `attributes` call through [AttributePageSource], so the
 * production contract, not a copy of it, is what the rules are proved on.
 */
class SyntheticDataSourceContractTest : PagedProviderContractSuite<WayAttribute>() {
    override fun createProvider(): PageSource<WayAttribute> =
        AttributePageSource(
            source = SyntheticElevationDataSource(),
            area = SyntheticFixture.bounds,
        )

    override fun expectedItems(provider: PageSource<WayAttribute>): List<WayAttribute> =
        SyntheticFixture.ways.map { it.attribute }.sortedBy { it.osmWayId }
}

/** Adapts `DataSource.attributes` onto the suite's paged-query shape. */
private class AttributePageSource(
    private val source: DataSource,
    private val area: BoundingBox,
) : PageSource<WayAttribute> {
    override val contract: ProviderContract = source.contract

    override val maxPageSize: Int = SyntheticElevationDataSource.MAX_PAGE_SIZE

    override suspend fun page(
        request: PageRequest,
        cancellation: CancellationHandle,
    ): Outcome<Page<WayAttribute>> =
        source
            .attributes(
                request =
                    AttributeRequest(
                        area = area,
                        timeMillis = FIXED_TIME_MILLIS,
                        limit = request.limit,
                        cursor = request.cursor,
                    ),
                cancellation = cancellation,
            ).map { page -> Page(items = page.items, nextCursor = page.nextCursor) }

    private companion object {
        const val FIXED_TIME_MILLIS = 1_792_000_000_000L
    }
}
