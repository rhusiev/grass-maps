package nl.r1a.grassmaps.plugins.downloads.ui

import nl.r1a.grassmaps.plugin.api.model.DownloadProblem
import nl.r1a.grassmaps.plugin.api.model.DownloadProblemKind
import nl.r1a.grassmaps.plugin.api.model.DownloadsData
import nl.r1a.grassmaps.plugin.api.model.RegionProblem
import nl.r1a.grassmaps.plugin.api.testing.fixture.CatalogFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sentences a typed problem shows (T-0504). A 401 without a token asks for
 * one; a 401 with a token says the token was refused.
 */
class DownloadsProblemTextTest {
    @Test
    fun aMissingTokenAsksForOneAndARejectedOneSaysSo() {
        assertEquals(
            R.string.downloads_token_required,
            DownloadsProblemText.key(DownloadProblemKind.TOKEN_REQUIRED),
        )
        assertEquals(
            R.string.downloads_token_rejected,
            DownloadsProblemText.key(DownloadProblemKind.TOKEN_REJECTED),
        )
    }

    @Test
    fun aFailedUpdateSaysTheInstalledMapStillWorks() {
        assertEquals(
            R.string.downloads_update_failed,
            DownloadsProblemText.key(DownloadProblemKind.UPDATE_FAILED),
        )
    }

    @Test
    fun everyProblemHasASentence() {
        for (kind in DownloadProblemKind.entries) {
            assertTrue("$kind has no sentence", DownloadsProblemText.key(kind) != 0)
        }
    }

    @Test
    fun anOfflineCatalogWithoutLocalDataExplainsWhyKyivCannotBeSearched() {
        val data = DownloadsData(catalogProblem = DownloadProblem(DownloadProblemKind.NETWORK))
        val idle = DownloadsScreen.state(data, downloadsEnabled = true)
        val search = DownloadsScreen.state(data, query = "Kyiv", downloadsEnabled = true)

        assertFalse(search.catalogLoaded)
        assertTrue(search.searchResults.isEmpty())
        assertEquals(R.string.downloads_catalog_offline, DownloadsProblemText.catalogKey(idle))
        assertEquals(R.string.downloads_catalog_offline, DownloadsProblemText.catalogKey(search))
    }

    @Test
    fun anOfflineRefreshKeepsTheLoadedCatalogSearchable() {
        val data = DownloadsData(catalog = CatalogTestData.real(), catalogProblem = DownloadProblem(DownloadProblemKind.NETWORK))
        val search = DownloadsScreen.state(data, query = "Ternopil", downloadsEnabled = true)

        assertTrue(search.catalogLoaded)
        assertEquals("ua-61", search.searchResults.single().region)
        assertEquals(R.string.downloads_no_connection, DownloadsProblemText.catalogKey(search))
    }

    @Test
    fun aMissingCatalogDoesNotHideTheReasonItFailed() {
        val pending = DownloadsScreen.state(DownloadsData(), query = "Kyiv", downloadsEnabled = true)
        val unauthorized = pending.copy(catalogProblem = DownloadProblem(DownloadProblemKind.TOKEN_REQUIRED))

        assertEquals(R.string.downloads_catalog_unavailable, DownloadsProblemText.catalogKey(pending))
        assertEquals(R.string.downloads_token_required, DownloadsProblemText.catalogKey(unauthorized))
        assertNull(DownloadsProblemText.catalogKey(unauthorized.copy(downloadsEnabled = false)))
    }

    @Test
    fun aNetworkFailureRecordedAgainstARegionStillSaysTheTransferStopped() {
        val data =
            DownloadsData(
                catalog = CatalogTestData.real(),
                problems = listOf(RegionProblem(CatalogFixture.LVIV, DownloadProblem(DownloadProblemKind.NETWORK))),
            )
        val state = DownloadsScreen.state(data, query = "Atlantis", downloadsEnabled = true)
        val failed = state.availableRows.first { it.region == CatalogFixture.LVIV }

        assertTrue(state.catalogLoaded)
        assertTrue(state.searchResults.isEmpty())
        assertNull(DownloadsProblemText.catalogKey(state))
        assertEquals(R.string.downloads_failed_network, failed.problem?.kind?.let(DownloadsProblemText::key))
    }
}
