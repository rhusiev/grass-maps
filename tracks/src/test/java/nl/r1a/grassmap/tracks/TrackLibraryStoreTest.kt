package nl.r1a.grassmap.tracks

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.Track
import nl.r1a.grassmap.plugin.api.model.TrackPoint
import nl.r1a.grassmap.plugin.api.model.TrackSegment
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The library metadata and the one-step delete undo on the file store (T-0414):
 * the name, description and folder live on the sidecar, a re-import keeps them,
 * and a deleted track comes back with its document and its folder.
 *
 * The reader is the same tiny test format as [FileTrackStoreTest]:
 * `lat,lon;lat,lon` is one track.
 */
class TrackLibraryStoreTest {
    private val root: File = Files.createTempDirectory("track-library-store").toFile()
    private val logs = mutableListOf<String>()

    @After
    fun deleteStore() {
        root.deleteRecursively()
    }

    @Test
    fun metadataIsWrittenToTheSidecarAndSurvivesAReload() =
        runTest {
            val store = store()
            store.save(record(SOURCE_A), SOURCE_A)

            assertEquals(
                Outcome.Success(Unit),
                store.setMetadata(
                    record(SOURCE_A).id,
                    TrackMetadata(name = "Ridges", description = "<b>Steep</b> steps", listId = "folder-1"),
                ),
            )

            val reloaded = store().summaries().single()
            assertEquals("Ridges", reloaded.name)
            assertEquals("<b>Steep</b> steps", reloaded.description)
            assertEquals("folder-1", reloaded.listId)
            assertArrayEquals(SOURCE_A, File(root, "${hashOf(SOURCE_A)}.gpx").readBytes())
        }

    @Test
    fun aReimportKeepsTheUsersNameDescriptionAndFolder() =
        runTest {
            val store = store()
            val record = record(SOURCE_A)
            store.save(record, SOURCE_A)
            store.setMetadata(record.id, TrackMetadata("Ridges", "<i>Wet</i> after rain", "folder-1"))

            val reimported = success(store.save(record, SOURCE_A))

            assertEquals("Ridges", reimported.name)
            assertEquals("<i>Wet</i> after rain", reimported.description)
            assertEquals("folder-1", reimported.listId)
            val summary = store.summaries().single()
            assertEquals("Ridges", summary.name)
            assertEquals("folder-1", summary.listId)
        }

    @Test
    fun aBlankMetadataValueIsStoredAsAbsent() =
        runTest {
            val store = store()
            val record = record(SOURCE_A)
            store.save(record, SOURCE_A)

            store.setMetadata(record.id, TrackMetadata("  ", "", " "))

            val summary = store.summaries().single()
            assertNull(summary.name)
            assertNull(summary.description)
            assertNull(summary.listId)
        }

    @Test
    fun theSummaryCarriesTheFirstPointForTheNearestOrder() =
        runTest {
            val store = store()
            store.save(record(SOURCE_A), SOURCE_A)

            val summary = store.summaries().single()

            assertEquals(49.84, summary.startLatitude ?: Double.NaN, COORDINATE_TOLERANCE)
            assertEquals(24.03, summary.startLongitude ?: Double.NaN, COORDINATE_TOLERANCE)
        }

    @Test
    fun metadataThatIsTooLongIsRejectedWithoutChangingTheSidecar() =
        runTest {
            val store = store()
            val record = record(SOURCE_A)
            store.save(record, SOURCE_A)

            val outcome = store.setMetadata(record.id, TrackMetadata("x".repeat(MAX_TRACK_NAME_LENGTH + 1), null, null))

            assertEquals(Outcome.Failure(AppError.InvalidInput), outcome)
            assertEquals("Track 0", store.summaries().single().name)
        }

    @Test
    fun deletingToTrashAndRestoringBringsBackTheDocumentAndTheFolder() =
        runTest {
            val store = store()
            val record = record(SOURCE_A)
            store.save(record, SOURCE_A)
            store.setMetadata(record.id, TrackMetadata("Ridges", "Note", "folder-1"))

            val removal = success(store.removeToTrash(record.id))

            assertTrue(store.summaries().isEmpty())
            assertFalse(File(root, "${hashOf(SOURCE_A)}.gpx").exists())

            assertEquals(Outcome.Success(Unit), store.restoreFromTrash(removal))

            val summary = store.summaries().single()
            assertEquals(record.id, summary.id)
            assertEquals("Ridges", summary.name)
            assertEquals("Note", summary.description)
            assertEquals("folder-1", summary.listId)
            assertArrayEquals(SOURCE_A, File(root, "${hashOf(SOURCE_A)}.gpx").readBytes())
            assertEquals(record.id, success(store.get(record.id)).id)
        }

    @Test
    fun undoingTheSecondDeleteKeepsTheFirstTracksParkedDocument() =
        runTest {
            val store = store()
            val first = record(SOURCE_A)
            val second = record(SOURCE_B)
            store.save(first, SOURCE_A)
            store.save(second, SOURCE_B)

            val firstRemoval = success(store.removeToTrash(first.id))
            val secondRemoval = success(store.removeToTrash(second.id))

            assertEquals(Outcome.Success(Unit), store.restoreFromTrash(secondRemoval))
            assertFalse(File(root, "${hashOf(SOURCE_A)}.gpx").exists())
            assertEquals(Outcome.Success(Unit), store.restoreFromTrash(firstRemoval))

            assertEquals(setOf(first.id, second.id), store.summaries().map { summary -> summary.id }.toSet())
            assertArrayEquals(SOURCE_A, File(root, "${hashOf(SOURCE_A)}.gpx").readBytes())
            assertArrayEquals(SOURCE_B, File(root, "${hashOf(SOURCE_B)}.gpx").readBytes())
        }

    @Test
    fun aReloadKeepsThePendingDeleteForItsUndo() =
        runTest {
            val store = store()
            val record = record(SOURCE_A)
            store.save(record, SOURCE_A)
            val removal = success(store.removeToTrash(record.id))

            store.reload()

            assertTrue(store.summaries().isEmpty())
            assertEquals(Outcome.Success(Unit), store.restoreFromTrash(removal))
            assertEquals(record.id, store.summaries().single().id)
        }

    @Test
    fun aReimportAfterTheDeleteMakesTheOldRemovalNotApplicable() =
        runTest {
            val store = store()
            val record = record(SOURCE_A)
            store.save(record, SOURCE_A)
            val removal = success(store.removeToTrash(record.id))
            store.save(record, SOURCE_A)

            assertEquals(Outcome.Failure(AppError.InvalidInput), store.restoreFromTrash(removal))
            assertEquals(record.id, store.summaries().single().id)
        }

    @Test
    fun aRemovalTokenCannotBeUsedTwice() =
        runTest {
            val store = store()
            val record = record(SOURCE_A)
            store.save(record, SOURCE_A)

            val removal = success(store.removeToTrash(record.id))
            store.restoreFromTrash(removal)

            assertEquals(Outcome.Failure(AppError.NotFound), store.restoreFromTrash(removal))
        }

    @Test
    fun aDeletedSharedDocumentStaysForTheOtherTrack() =
        runTest {
            val store = store()
            val first = record(SOURCE_MULTI, index = 0, count = 2)
            val second = record(SOURCE_MULTI, index = 1, count = 2)
            store.save(first, SOURCE_MULTI)
            store.save(second, null)

            store.removeToTrash(first.id)

            assertEquals(listOf(second.id), store.summaries().map { summary -> summary.id })
            assertTrue(File(root, "${hashOf(SOURCE_MULTI)}.gpx").isFile)
        }

    private fun store(): FileTrackStore =
        FileTrackStore(
            root = root,
            reader = reader(),
            clock = { SAVED_AT_MILLIS },
            log = { message -> logs += message },
        )

    private fun reader(): TrackDocumentReader =
        TrackDocumentReader { bytes ->
            val text = bytes.decodeToString()
            if (text.startsWith("bad")) {
                Outcome.Failure(AppError.CorruptData)
            } else {
                Outcome.Success(text.split('|').map { part -> trackOf(part) })
            }
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

    private fun record(
        bytes: ByteArray,
        index: Int = 0,
        count: Int = 1,
    ): StoredTrack {
        val track = trackOf(bytes.decodeToString().split('|')[index])
        return StoredTrack(
            id = TrackIdentity.of(bytes, index, count),
            name = "Track $index",
            track = track,
            analysis = TrackAnalyzer().analyze(track),
            style = TrackPalette.styleFor(TrackIdentity.of(bytes, index, count)),
            trackIndex = index,
        )
    }

    private fun hashOf(bytes: ByteArray): String = TrackIdentity.documentHash(bytes)

    private fun <T> success(outcome: Outcome<T>): T =
        when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("unexpected failure: ${outcome.error}")
        }

    private companion object {
        const val SAVED_AT_MILLIS = 1_762_000_000_000L
        const val COORDINATE_TOLERANCE = 1e-9
        val SOURCE_A = "49.84000,24.03000;49.84010,24.03010".toByteArray()
        val SOURCE_B = "49.85000,24.04000;49.85010,24.04010".toByteArray()
        val SOURCE_MULTI = "49.86000,24.05000;49.86010,24.05010|49.87000,24.06000".toByteArray()
    }
}
