package nl.r1a.grassmap.app

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nl.r1a.grassmap.ui.shell.TrackImportFailure
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The share path as Android delivers it (T-0416): an `ACTION_SEND` intent whose
 * payload is a `content:` URI, read through the resolver, imported through the
 * real `file.gpx` provider, and announced in the notice.
 *
 * The document comes from [SharedTrackTestProvider], a debug-only provider, so
 * the read exercises the provider's type, display name and size, not a file
 * path. The activity is started exactly like a share from another app starts
 * it. This is the test the `adb` commands in the README mirror.
 */
@RunWith(AndroidJUnit4::class)
class ShareImportInstrumentedTest {
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<GrassMapsApplication>()
        container = application.container
        runBlocking { clearTracks() }
        container.trackShare.notice.value
            ?.let { state -> container.trackShare.dismiss(state.sequence) }
    }

    @After
    fun tearDown() {
        runBlocking { clearTracks() }
        container.trackShare.notice.value
            ?.let { state -> container.trackShare.dismiss(state.sequence) }
    }

    @Test
    fun anActionSendOfAContentUriImportsTheDocumentAndNamesIt() {
        val outcome = shared(WALK_URI)

        assertNull(outcome.failure)
        assertEquals(1, outcome.trackCount)
        assertEquals(TRACK_NAME, outcome.names.single())
        val stored = runBlocking { container.tracks.storedTracks() }
        assertEquals(1, stored.size)
        assertEquals(TRACK_NAME, stored.single().name)
    }

    @Test
    fun sharingTheSameDocumentAgainKeepsOneRecord() {
        val first = shared(WALK_URI)
        val id =
            runBlocking {
                container.tracks
                    .storedTracks()
                    .single()
                    .id
            }
        val second = shared(WALK_URI)

        assertEquals(first.names, second.names)
        val stored = runBlocking { container.tracks.storedTracks() }
        assertEquals(1, stored.size)
        assertEquals(id, stored.single().id)
    }

    @Test
    fun aDocumentThatIsNotATrackIsReportedAsSuch() {
        val outcome = shared(NOTES_URI)

        assertEquals(TrackImportFailure.NotATrack, outcome.failure)
        assertTrue(runBlocking { container.tracks.storedTracks() }.isEmpty())
    }

    @Test
    fun aProviderThatRefusesTheReadIsReportedAsUnreadable() {
        val outcome = shared(UNREADABLE_URI)

        assertEquals(TrackImportFailure.Unreadable, outcome.failure)
        assertTrue(runBlocking { container.tracks.storedTracks() }.isEmpty())
    }

    /** Sends the share to the activity and returns the notice's outcome. */
    private fun shared(uri: String) =
        ActivityScenario.launch<MainActivity>(shareIntent(uri)).use {
            runBlocking {
                withTimeout(IMPORT_TIMEOUT_MILLIS) {
                    container.trackShare.notice
                        .first { state -> state != null }
                        ?.outcome
                }
            } ?: error("the import published no notice")
        }

    private fun shareIntent(uri: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = SharedTrackTestProvider.GPX_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setClass(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        }

    private suspend fun clearTracks() {
        container.tracks.storedTracks().forEach { track -> container.tracks.remove(track.id) }
    }

    private companion object {
        const val TRACK_NAME = "Mock walk around Lviv"
        const val IMPORT_TIMEOUT_MILLIS = 15_000L
        const val WALK_URI = "content://${SharedTrackTestProvider.AUTHORITY}/${SharedTrackTestProvider.WALK}"
        const val NOTES_URI = "content://${SharedTrackTestProvider.AUTHORITY}/${SharedTrackTestProvider.NOTES}"
        const val UNREADABLE_URI = "content://${SharedTrackTestProvider.AUTHORITY}/${SharedTrackTestProvider.UNREADABLE}"
    }
}
