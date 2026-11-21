package nl.r1a.grassmaps.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmaps.app.generated.GeneratedPlugins
import nl.r1a.grassmaps.core.PluginHost
import nl.r1a.grassmaps.core.ProviderRegistry
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.ui.shell.TrackImportFailure
import nl.r1a.grassmaps.ui.shell.TrackImportOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The share-sheet import over the real `file.gpx` provider (T-0416).
 *
 * A share is parsed first and waits in [TrackShareImport.confirmation]; only
 * `confirm` writes to the library, and `cancel` writes nothing and says
 * nothing. The activity's part - the content resolver and the intent delivery -
 * is the device check over `adb`; this test drives the same rule and the same
 * importer with the reader seam the activity supplies.
 */
class TrackShareImportTest {
    @Test
    fun aShareAsksBeforeItStoresTheOneTrack() =
        runTest {
            val tracks = trackAccess()
            val shared = TrackShareImport(tracks = { tracks }, log = {})

            val confirmation = requireNotNull(shared.prepare(SHARE_URI, reader(gpxBytes())))

            assertEquals(1, confirmation.trackCount)
            assertTrue("nothing is stored before confirm", tracks.storedTracks().isEmpty())
            assertNull("no notice before confirm", shared.notice.value)

            val state = requireNotNull(shared.confirm())

            assertEquals(TrackImportOutcome(names = listOf(TRACK_NAME)), state.outcome)
            assertEquals(1L, state.sequence)
            assertEquals(TRACK_NAME, tracks.storedTracks().single().name)
            assertEquals(state, shared.notice.value)
            assertNull(shared.confirmation.value)
        }

    @Test
    fun aTwoTrackShareAsksAndImportsBothOnlyOnConfirm() =
        runTest {
            val tracks = trackAccess()
            val shared = TrackShareImport(tracks = { tracks }, log = {})

            val confirmation =
                requireNotNull(
                    shared.prepare(SHARE_URI, reader(multiTrackBytes(), displayName = MULTI_DISPLAY_NAME)),
                )

            assertEquals(2, confirmation.trackCount)
            assertTrue(tracks.storedTracks().isEmpty())

            val state = requireNotNull(shared.confirm())

            assertEquals(2, state.outcome.trackCount)
            assertEquals(listOf("Morning walk", "Evening walk"), state.outcome.names)
            assertEquals(2, tracks.storedTracks().size)
        }

    @Test
    fun aCancelledShareImportsNothingAndPublishesNoNotice() =
        runTest {
            val tracks = trackAccess()
            val shared = TrackShareImport(tracks = { tracks }, log = {})

            requireNotNull(shared.prepare(SHARE_URI, reader(multiTrackBytes(), displayName = MULTI_DISPLAY_NAME)))
            shared.cancel()

            assertNull(shared.confirmation.value)
            assertTrue("cancel writes nothing", tracks.storedTracks().isEmpty())
            assertNull("cancel says nothing", shared.notice.value)
            assertNull("there is nothing left to confirm", shared.confirm())
        }

    @Test
    fun aOneTrackShareCarriesTheSingularCount() =
        runTest {
            val tracks = trackAccess()
            val shared = TrackShareImport(tracks = { tracks }, log = {})

            val confirmation = requireNotNull(shared.prepare(SHARE_URI, reader(gpxBytes())))

            assertEquals(1, confirmation.trackCount)
        }

    @Test
    fun theConfirmationCarriesTheSendersFileName() =
        runTest {
            val tracks = trackAccess()
            val shared = TrackShareImport(tracks = { tracks }, log = {})

            val confirmation = requireNotNull(shared.prepare(SHARE_URI, reader(gpxBytes())))

            assertEquals(DISPLAY_NAME, confirmation.fileName)
        }

    @Test
    fun aGpxTypedDocumentWithoutADisplayNameStillAsks() =
        runTest {
            val tracks = trackAccess()
            val shared = TrackShareImport(tracks = { tracks }, log = {})

            requireNotNull(shared.prepare(SHARE_URI, reader(gpxBytes(), mimeType = GPX_MIME)))
            val state = requireNotNull(shared.confirm())

            assertEquals(TRACK_NAME, state.outcome.names.single())
        }

    @Test
    fun sharingTheSameFileAgainUpdatesTheOneRecord() =
        runTest {
            val tracks = trackAccess()
            val shared = TrackShareImport(tracks = { tracks }, log = {})

            requireNotNull(shared.prepare(SHARE_URI, reader(gpxBytes())))
            val first = requireNotNull(shared.confirm())
            val firstId = tracks.storedTracks().single().id
            requireNotNull(shared.prepare(SHARE_URI, reader(gpxBytes())))
            val second = requireNotNull(shared.confirm())

            assertEquals(first.outcome, second.outcome)
            assertEquals(firstId, tracks.storedTracks().single().id)
            assertEquals(1, tracks.summaries().size)
            assertTrue("a second share is a new notice", second.sequence > first.sequence)
        }

    @Test
    fun aDocumentThatIsNotATrackSaysSoAndDoesNotAsk() =
        runTest {
            val tracks = trackAccess()
            val shared = TrackShareImport(tracks = { tracks }, log = {})

            val confirmation = shared.prepare(SHARE_URI, reader("not a track".toByteArray(), displayName = "notes.txt"))

            assertNull("nothing to confirm", confirmation)
            assertNull(shared.confirmation.value)
            assertEquals(
                TrackImportOutcome(failure = TrackImportFailure.NotATrack),
                requireNotNull(shared.notice.value).outcome,
            )
            assertTrue(tracks.storedTracks().isEmpty())
        }

    @Test
    fun aUriThatCannotBeReadSaysTheFileCouldNotBeOpened() =
        runTest {
            val tracks = trackAccess()
            val shared = TrackShareImport(tracks = { tracks }, log = {})

            assertNull(shared.prepare(SHARE_URI) { Outcome.Failure(AppError.NotFound) })

            assertNull(shared.confirmation.value)
            assertEquals(
                TrackImportOutcome(failure = TrackImportFailure.Unreadable),
                requireNotNull(shared.notice.value).outcome,
            )
        }

    @Test
    fun aRefusedDocumentIsReportedAsNotATrackWithoutAsking() =
        runTest {
            val tracks = trackAccess()
            val shared = TrackShareImport(tracks = { tracks }, log = {})

            assertNull(shared.prepare(SHARE_URI) { Outcome.Failure(AppError.InvalidInput) })

            assertNull(shared.confirmation.value)
            assertEquals(
                TrackImportOutcome(failure = TrackImportFailure.NotATrack),
                requireNotNull(shared.notice.value).outcome,
            )
            assertTrue(tracks.storedTracks().isEmpty())
        }

    @Test
    fun openingTheNoticeShowsTheImportedTrackAndClosesIt() =
        runTest {
            val tracks = trackAccess()
            val shared = TrackShareImport(tracks = { tracks }, log = {})
            requireNotNull(shared.prepare(SHARE_URI, reader(gpxBytes())))
            requireNotNull(shared.confirm())

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
            requireNotNull(shared.prepare(SHARE_URI, reader(gpxBytes())))
            val first = requireNotNull(shared.confirm())
            requireNotNull(shared.prepare(SHARE_URI, reader(gpxBytes())))
            val second = requireNotNull(shared.confirm())

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

    private fun gpxBytes(): ByteArray = resourceBytes(FIXTURE_RESOURCE)

    private fun multiTrackBytes(): ByteArray = resourceBytes(MULTI_TRACK_RESOURCE)

    private fun resourceBytes(resource: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(resource)) {
            "missing test resource $resource"
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
        const val MULTI_DISPLAY_NAME = "two-walks.gpx"
        const val TRACK_NAME = "Mock walk around Lviv"
        const val GPX_MIME = "application/gpx+xml"
        const val FIXTURE_RESOURCE = "location/mock-walk.gpx"
        const val MULTI_TRACK_RESOURCE = "tracks/osmand-multi-track.gpx"
    }
}
