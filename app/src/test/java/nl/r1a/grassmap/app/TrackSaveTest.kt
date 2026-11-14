package nl.r1a.grassmap.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.app.generated.GeneratedPlugins
import nl.r1a.grassmap.core.PluginHost
import nl.r1a.grassmap.core.ProviderRegistry
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.DocumentSaveResult
import nl.r1a.grassmap.plugin.api.host.PlatformActions
import nl.r1a.grassmap.plugin.api.host.PlatformFile
import nl.r1a.grassmap.tracks.ImportReport
import nl.r1a.grassmap.tracks.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-0410's user entry point: the library's Save action builds the selected
 * track's GPX, offers it to the system picker under the track's name, and
 * reports the save, the dismissal or the typed error. The picker itself is
 * Android and is exercised on a device; these tests drive the same seam the
 * activity binds with a recording platform.
 */
class TrackSaveTest {
    @Test
    fun savingOneSelectedTrackBuildsItsGpxUnderTheTrackName() =
        runTest {
            val access = access()
            val record = import(access, "garmin-connect-hike.gpx").tracks.single()
            val actions = RecordingActions()

            assertEquals(TrackSaveResult.Saved, saveTrack(TrackExport(access, actions), record.id))

            assertEquals(listOf("save:Lviv hills hike.gpx:application/gpx+xml:${actions.savedBytes.size}"), actions.calls)
            val text = actions.savedBytes.toString(Charsets.UTF_8)
            assertTrue(text.contains("version=\"1.1\""))
            assertTrue(text.contains("<name>${record.track.name}</name>"))
        }

    @Test
    fun thePickerIsAskedForAGpxWithTheTrackNameAsTheSuggestedName() =
        runTest {
            val access = access()
            val record = import(access, "garmin-connect-hike.gpx").tracks.single()
            val file = exported(TrackExport(access, RecordingActions()).fileFor(record.id))

            val request = DocumentCreateRequest.of(file)

            assertEquals("Lviv hills hike.gpx", request.suggestedName)
            assertEquals("application/gpx+xml", request.mimeType)
        }

    @Test
    fun aDismissedPickerIsCancelledAndNotAnError() =
        runTest {
            val access = access()
            val record = import(access, "strava-run.gpx").tracks.single()
            val actions = RecordingActions()
            actions.saveResult = Outcome.Success(DocumentSaveResult.CANCELLED)

            assertEquals(TrackSaveResult.Cancelled, saveTrack(TrackExport(access, actions), record.id))
        }

    @Test
    fun aRefusedSaveKeepsTheTypedError() =
        runTest {
            val access = access()
            val record = import(access, "strava-run.gpx").tracks.single()
            val actions = RecordingActions()
            actions.saveResult = Outcome.Failure(AppError.NotReady)

            assertEquals(
                TrackSaveResult.Failed(AppError.NotReady),
                saveTrack(TrackExport(access, actions), record.id),
            )
        }

    @Test
    fun aMissingTrackIsATypedFailureAndThePickerNeverOpens() =
        runTest {
            val actions = RecordingActions()

            assertEquals(
                TrackSaveResult.Failed(AppError.NotFound),
                saveTrack(TrackExport(access(), actions), TrackId("missing")),
            )
            assertTrue(actions.calls.isEmpty())
        }

    private fun access(): TrackAccess =
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
            tracksRoot = null,
        )

    private suspend fun import(
        access: TrackAccess,
        file: String,
    ): ImportReport =
        when (val outcome = access.import(fileName = file, bytes = bytes("tracks/$file"))) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("$file did not import: ${outcome.error}")
        }

    private fun exported(outcome: Outcome<PlatformFile>): PlatformFile =
        when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("export failed: ${outcome.error}")
        }

    private fun bytes(resource: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(resource)) {
            "missing test resource $resource"
        }.use { it.readBytes() }

    private class RecordingActions : PlatformActions {
        val calls = mutableListOf<String>()
        var savedBytes: ByteArray = ByteArray(0)
        var saveResult: Outcome<DocumentSaveResult> = Outcome.Success(DocumentSaveResult.SAVED)

        override fun copyToClipboard(text: String): Outcome<Unit> = Outcome.Success(Unit)

        override fun shareText(
            text: String,
            subject: String?,
        ): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun shareFile(
            file: PlatformFile,
            subject: String?,
        ): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun saveFile(file: PlatformFile): Outcome<DocumentSaveResult> {
            savedBytes = file.bytes
            calls += "save:${file.fileName}:${file.mimeType}:${file.bytes.size}"
            return saveResult
        }

        override fun openUri(uri: String): Outcome<Unit> = Outcome.Success(Unit)
    }
}
