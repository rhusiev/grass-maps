package nl.r1a.grassmap.plugin.api.testing

import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.contract.Page
import nl.r1a.grassmap.plugin.api.contract.PageRequest
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.provider.Provider

/**
 * The paged-query shape exercised by [PagedProviderContractSuite].
 *
 * This is test-only. The production contracts (DataSource.attributes,
 * PlaceProvider.search, and so on) stay in `plugin-api.provider`; their tests
 * adapt them onto this interface so one suite covers the paging rules they
 * share.
 */
interface PageSource<T> : Provider {
    /** The largest [PageRequest.limit] this provider accepts; at least 1. */
    val maxPageSize: Int

    suspend fun page(
        request: PageRequest,
        cancellation: CancellationHandle,
    ): Outcome<Page<T>>
}
