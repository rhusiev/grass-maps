package nl.r1a.grassmap.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.app.generated.GeneratedPlugins
import nl.r1a.grassmap.core.PluginHost
import nl.r1a.grassmap.core.ProviderRegistry
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.ui.shell.TrackImportFailure
import nl.r1a.grassmap.ui.shell.TrackImportOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The share-sheet import over the real `file.gpx` provider (T-0416): an
 * `ACTION_SEND` of the committed GPX becomes a stored track, and sharing the
 * same bytes again updates that one record.
 *
 * The activity's part - the content resolver and the intent delivery - is the
 * device check over `adb`; this test drives the same rule and the same importer
 * with the reader seam the activity supplies.
 */
class TrackShareImportTest {
    @Test
    fun anActionSendOfAGpxImportsTheTrackAndNoticesIt() =
        runTest {
            val tracks = trackAccess()
            val shared = TrackShareImport(tracks = { tracks }, log = {})

            val state = requireNotNull(shared.import(SHARE_URI, reader(gpxBytes())))

            assertEquals(TrackImportOutcome(names = listOf(TRACK_NAME)), state.outcome)
            assertEquals(1L, state.sequence)
            assertEquals(TRACK_NAME, tracks.storedTracks().single().name)
            assertEquals(state, shared.notice.value)
        }

    @Test
    fun aGpxTypedDocumentWithoutADisplayNameStillImports() =
        runTest {
            val tracks = trackAccess()
            val shared = TrackShareImport(tracks = { tracks }, log = {})

            val state = requireNotNull(shared.import(SHARE_URI, reader(gpxBytes(), mimeType = GPX_MIME)))

            assertEquals(TRACK_NAME, state.outcome.names.single())
        }

    @Test
    fun sharingTheSameFileAgainUpdatesTheOneRecord() =
        runTest {
            val tracks = trackAccess()
            val shared = TrackShareImport(tracks = { tracks }, log = {})

            val first = requireNotNull(shared.import(SHARE_URI, reader(gpxBytes())))
            val firstId = tracks.storedTracks().single().id
            val second = requireNotNull(shared.import(SHARE_URI, reader(gpxBytes())))

            assertEquals(first.outcome, second.outcome)
            assertEquals(firstId, tracks.storedTracks().single().id)
            assertEquals(1, tracks.summaries().size)
            assertTrue("a second share is a new notice", second.sequence > first.sequence)
        }

    @Test
    fun aDocumentThatIsNotATrackSaysSo() =
        runTest {
            val tracks = trackAccess()
            val shared = TrackShareImport(tracks = { tracks }, log = {})

            val state =
                requireNotNull(
                    shared.import(SHARE_URI, reader("not a track".toByteArray(), displayName = "notes.txt")),
                )

            assertEquals(TrackImportOutcome(failure = TrackImportFailure.NotATrack), state.outcome)
            assertTrue(tracks.storedTracks().isEmpty())
        }

    @Test
    fun aUriThatCannotBeReadSaysTheFileCouldNotBeOpened() =
        runTest {
            val tracks = trackAccess()
            val shared = TrackShareImport(tracks = { tracks }, log = {})

            val state = requireNotNull(shared.import(SHARE_URI) { Outcome.Failure(AppError.NotFound) })

            assertEquals(TrackImportOutcome(failure = TrackImportFailure.Unreadable), state.outcome)
        }

    @Test
    fun aRefusedDocumentIsReportedAsNotATrack() =
        runTest {
            val tracks = trackAccess()
            val shared = TrackShareImport(tracks = { tracks }, log = {})

            val state = requireNotNull(shared.import(SHARE_URI) { Outcome.Failure(AppError.InvalidInput) })

            assertEquals(TrackImportOutcome(failure = TrackImportFailure.NotATrack), state.outcome)
            assertTrue(tracks.storedTracks().isEmpty())
        }

    @Test
    fun openingTheNoticeShowsTheImportedTrackAndClosesIt() =
        runTest {
            val tracks = trackAccess()
            val shared = TrackShareImport(tracks = { tracks }, log = {})
            requireNotNull(shared.import(SHARE_URI, reader(gpxBytes())))

            shared.openImported()

            assertEquals(TRACK_NAME, TrackAnalysisSession.track.value?.name)
            assertNull(shared.notice.value)
            TrackAnalysisSession.hide()
        }

    @Test
    fun anOlderNoticeCannotDismissANewerOne() =
        runTest {
            val tracks = trackAccess()
            val shared = TrackShareImport(tracks = { tracks }, log = {})
            val first = requireNotNull(shared.import(SHARE_URI, reader(gpxBytes())))
            val second = requireNotNull(shared.import(SHARE_URI, reader(gpxBytes())))

            shared.dismiss(first.sequence)

            assertEquals(second, shared.notice.value)
            shared.dismiss(second.sequence)
            assertNull(shared.notice.value)
        }

    private fun reader(
        bytes: ByteArray,
        displayName: String? = DISPLAY_NAME,
        mimeType: String? = null,
    ): SharedDocumentReader =
        SharedDocumentReader { _ ->
            Outcome.Success(SharedDocument(displayName = displayName, mimeType = mimeType, bytes = bytes))
        }

    private fun gpxBytes(): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(FIXTURE_RESOURCE)) {
            "missing test resource $FIXTURE_RESOURCE"
        }.use { it.readBytes() }

    private fun trackAccess(): TrackAccess =
        TrackAccess(
            registry =
                ProviderRegistry(
                    PluginHost(
                        plugins = GeneratedPlugins.plugins,
                        streams = emptyList(),
                        commands = emptyList(),
                        dispatcher = Dispatchers.Unconfined,
                    ),
                ),
            providers = GeneratedPlugins.providers,
        )

    private companion object {
        const val SHARE_URI = "content://com.example.share/1"
        const val DISPLAY_NAME = "znesinnia-ridges.gpx"
        const val TRACK_NAME = "Mock walk around Lviv"
        const val GPX_MIME = "application/gpx+xml"
        const val FIXTURE_RESOURCE = "location/mock-walk.gpx"
    }
}
