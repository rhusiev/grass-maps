package nl.r1a.grassmaps.plugins.downloads.ui

import nl.r1a.grassmaps.plugin.api.model.DownloadProblemKind
import org.junit.Assert.assertEquals
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
}
