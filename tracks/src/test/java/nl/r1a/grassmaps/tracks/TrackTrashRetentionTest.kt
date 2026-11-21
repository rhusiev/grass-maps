package nl.r1a.grassmaps.tracks

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.ImportedEntities
import nl.r1a.grassmaps.plugin.api.model.Track
import nl.r1a.grassmaps.plugin.api.model.TrackPoint
import nl.r1a.grassmaps.plugin.api.model.TrackSegment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The seven-day retention of the delete undo (review 4.1): the clock starts at
 * the deletion, a purge removes only expired tokens, and an undo after the
 * window answers with a typed NotFound instead of restoring a missing file.
 */
class TrackTrashRetentionTest {
    private val root: File = Files.createTempDirectory("track-trash-retention").toFile()
    private var now = DELETED_AT_MILLIS

    @After
    fun deleteStore() {
        root.deleteRecursively()
    }

    @Test
    fun deletingRecordsTheDeletionTimeOnTheParkedToken() =
        runTest {
            val store = store()
            val record = record(SOURCE_A)
            store.save(record, SOURCE_A)

            store.removeToTrash(record.id)

            assertEquals(DELETED_AT_MILLIS, TrackTrash.deletedAtMillis(parkedToken()))
        }

    @Test
    fun aTrackDeletedEightDaysAgoIsPurgedAndItsUndoFailsTyped() =
        runTest {
            val store = store()
            val record = record(SOURCE_A)
            store.save(record, SOURCE_A)
            val removal = success(store.removeToTrash(record.id))
            val token = parkedToken()

            now += 8 * DAY_MILLIS

            assertEquals(1, TrackTrash.purge(root, now))
            assertFalse(token.exists())
            assertEquals(Outcome.Failure(AppError.NotFound), store.restoreFromTrash(removal))
            assertFalse(File(root, "${hashOf(SOURCE_A)}.gpx").exists())
            assertTrue(store.summaries().isEmpty())
        }

    @Test
    fun aTrackDeletedYesterdaySurvivesThePurgeAndStillRestores() =
        runTest {
            val store = store()
            val record = record(SOURCE_A)
            store.save(record, SOURCE_A)
            val removal = success(store.removeToTrash(record.id))
            val token = parkedToken()

            now += DAY_MILLIS

            assertEquals(0, TrackTrash.purge(root, now))
            assertTrue(token.isDirectory)
            assertEquals(Outcome.Success(Unit), store.restoreFromTrash(removal))
            assertEquals(record.id, store.summaries().single().id)
            assertTrue(File(root, "${hashOf(SOURCE_A)}.gpx").isFile)
            assertFalse(token.exists())
        }

    @Test
    fun aTokenFromAnOlderBuildFallsBackToItsDirectoryTime() =
        runTest {
            val store = store()
            val record = record(SOURCE_A)
            store.save(record, SOURCE_A)
            store.removeToTrash(record.id)
            val token = parkedToken()
            assertTrue(File(token, TrackTrash.TIMESTAMP_FILE).delete())
            assertTrue(token.setLastModified(DELETED_AT_MILLIS - 8 * DAY_MILLIS))

            assertEquals(1, TrackTrash.purge(root, now))
            assertFalse(token.exists())
        }

    private fun parkedToken(): File =
        File(root, TrackTrash.DIRECTORY)
            .listFiles()
            .orEmpty()
            .single { it.isDirectory }

    private fun store(): FileTrackStore =
        FileTrackStore(
            root = root,
            reader = reader(),
            clock = { now },
        )

    private fun reader(): TrackDocumentReader =
        TrackDocumentReader { bytes ->
            val text = bytes.decodeToString()
            Outcome.Success(ImportedEntities(tracks = listOf(trackOf(text))))
        }

    private fun trackOf(text: String): Track =
        Track(
            name = null,
            segments =
                listOf(
                    TrackSegment(
                        text
                            .split(';')
                            .filter { it.isNotBlank() }
                            .map { pair ->
                                val (latitude, longitude) = pair.split(',').map { it.toDouble() }
                                TrackPoint(GeoPoint(latitude, longitude), elevationMeters = 100.0)
                            },
                    ),
                ),
        )

    private fun record(bytes: ByteArray): StoredTrack {
        val track = trackOf(bytes.decodeToString())
        val id = TrackIdentity.of(bytes, 0)
        return StoredTrack(
            id = id,
            name = "Track 0",
            track = track,
            analysis = TrackAnalyzer().analyze(track),
            style = TrackPalette.styleFor(id),
            trackIndex = 0,
        )
    }

    private fun hashOf(bytes: ByteArray): String = TrackIdentity.documentHash(bytes)

    private fun <T> success(outcome: Outcome<T>): T =
        when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("unexpected failure: ${outcome.error}")
        }

    private companion object {
        const val DELETED_AT_MILLIS = 1_762_000_000_000L
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
        val SOURCE_A = "49.84000,24.03000;49.84010,24.03010".toByteArray()
    }
}
