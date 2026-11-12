package nl.r1a.grassmap.tracks

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.Track
import nl.r1a.grassmap.plugin.api.model.TrackPoint
import nl.r1a.grassmap.plugin.api.model.TrackSegment
import nl.r1a.grassmap.plugin.api.model.TrackStyle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The file store's contract: one document plus one sidecar per track, a content
 * hash for identity, atomic replacement and a reload that skips only what it
 * cannot use.
 *
 * The reader is a tiny test document format so the store is measured without
 * the GPX plugin: `lat,lon;lat,lon` is one track, and `|` separates documents
 * with several tracks.
 */
class FileTrackStoreTest {
    private val root: File = Files.createTempDirectory("track-store").toFile()
    private val logs = mutableListOf<String>()

    @After
    fun deleteStore() {
        root.deleteRecursively()
    }

    @Test
    fun aSavedTrackIsOneDocumentAndOneSidecar() =
        runTest {
            val store = store()
            val record = record(SOURCE_A)

            val saved = success(store.save(record, SOURCE_A))

            assertEquals(record.id, saved.id)
            assertTrue(File(root, "${hashOf(SOURCE_A)}.gpx").isFile)
            assertTrue(File(root, "${record.id.value}.json").isFile)
            assertEquals(SOURCE_A.decodeToString(), File(root, "${hashOf(SOURCE_A)}.gpx").readText())
            assertEquals(listOf(saved.toMapTrack()), store.drawn.value)
        }

    @Test
    fun aReloadedStoreReturnsTheSameRecordAndCachedProfile() =
        runTest {
            val first = store()
            val record = success(first.save(record(SOURCE_A), SOURCE_A))
            val before = first.summaries().single()

            val reopened = store()
            assertEquals(Outcome.Success(Unit), reopened.reload())

            val after = success(reopened.get(record.id))
            assertEquals(record.track, after.track)
            assertEquals(record.analysis, after.analysis)
            assertEquals(record.style, after.style)
            assertEquals(record.trackIndex, after.trackIndex)
            assertEquals(before, reopened.summaries().single())
            assertEquals(record.id, reopened.all().single().id)
        }

    @Test
    fun theSameBytesKeepOneIdentityAcrossStores() =
        runTest {
            val record = success(store().save(record(SOURCE_A), SOURCE_A))
            val again = success(store().save(record(SOURCE_A), SOURCE_A))

            assertEquals(record.id, again.id)
            assertEquals(1, root.listFiles().orEmpty().count { it.name.endsWith(".json") })
            assertEquals(1, root.listFiles().orEmpty().count { it.name.endsWith(".gpx") })
        }

    @Test
    fun aStoredColourSurvivesAReimport() =
        runTest {
            val store = store()
            val record = success(store.save(record(SOURCE_A), SOURCE_A))
            val recoloured = TrackStyle(colour = "#00897B", widthDp = 7f)
            assertEquals(Outcome.Success(Unit), store.setStyle(record.id, recoloured))

            val reimported = success(store.save(record.copy(style = TrackStyle()), SOURCE_A))

            assertEquals(recoloured, reimported.style)
            assertEquals(recoloured, success(store.get(record.id)).style)
            assertEquals(
                recoloured,
                store.drawn.value
                    .single()
                    .style,
            )
        }

    @Test
    fun aCorruptSidecarIsSkippedAndTheRestLoads() =
        runTest {
            val store = store()
            success(store.save(record(SOURCE_A), SOURCE_A))
            success(store.save(record(SOURCE_B), SOURCE_B))
            val corrupt = File(root, "${record(SOURCE_B).id.value}.json")
            corrupt.writeText("{not a sidecar")

            val reopened = store()
            assertEquals(Outcome.Success(Unit), reopened.reload())

            assertEquals(1, reopened.all().size)
            assertEquals(record(SOURCE_A).id, reopened.all().single().id)
            assertTrue(logs.any { it.contains("unreadable") })
        }

    @Test
    fun aDocumentWhoseBytesNoLongerMatchItsHashIsSkipped() =
        runTest {
            val store = store()
            val record = success(store.save(record(SOURCE_A), SOURCE_A))
            File(root, "${hashOf(SOURCE_A)}.gpx").writeText("49.1,24.1")

            val reopened = store()
            assertEquals(Outcome.Success(Unit), reopened.reload())

            assertEquals(emptyList<StoredTrack>(), reopened.all())
            assertTrue(logs.any { it.contains("hash does not match") })
        }

    @Test
    fun aDocumentTheReaderRejectsIsSkipped() =
        runTest {
            val store = store()
            success(store.save(record(SOURCE_A), SOURCE_A))
            val rejectedBytes = "bad".toByteArray()
            val rejected =
                StoredTrack(
                    id = TrackIdentity.of(rejectedBytes, index = 0, count = 1),
                    name = "Rejected",
                    track = trackOf(SOURCE_A.decodeToString()),
                    analysis = TrackAnalyzer().analyze(trackOf(SOURCE_A.decodeToString())),
                    style = TrackPalette.styleFor(TrackIdentity.of(rejectedBytes, index = 0, count = 1)),
                )
            success(store.save(rejected, rejectedBytes))

            val reopened = store()
            assertEquals(Outcome.Success(Unit), reopened.reload())

            assertEquals(1, reopened.all().size)
            assertTrue(logs.any { it.contains("could not be read") })
        }

    @Test
    fun removingOneTrackOfAMultiTrackDocumentKeepsTheDocument() =
        runTest {
            val store = store()
            val bytes = SOURCE_MULTI
            val first = success(store.save(record(bytes, index = 0, count = 2), bytes))
            val second = success(store.save(record(bytes, index = 1, count = 2), bytes))
            val document = File(root, "${hashOf(bytes)}.gpx")

            assertEquals(Outcome.Success(Unit), store.remove(first.id))
            assertTrue(document.isFile)
            assertEquals(listOf(second.id), store.all().map { it.id })

            assertEquals(Outcome.Success(Unit), store.remove(second.id))
            assertFalse(document.exists())
            assertEquals(emptyList<StoredTrack>(), store.all())
        }

    @Test
    fun aPartialFileFromAnInterruptedWriteIsIgnored() =
        runTest {
            val store = store()
            val record = success(store.save(record(SOURCE_A), SOURCE_A))
            File(root, ".${record.id.value}.json.part").writeText("garbage")
            File(root, ".${hashOf(SOURCE_A)}.gpx.part").writeText("garbage")

            val reopened = store()
            assertEquals(Outcome.Success(Unit), reopened.reload())

            assertEquals(1, reopened.all().size)
            assertEquals(record.id, reopened.all().single().id)
        }

    @Test
    fun anInvalidStyleOrIdentifierIsRejectedWithoutWriting() =
        runTest {
            val store = store()
            val badStyle = record(SOURCE_A).copy(style = TrackStyle(colour = "blue"))
            val badId = record(SOURCE_B).copy(id = TrackId("not-a-hash"))

            assertEquals(Outcome.Failure(AppError.InvalidInput), store.save(badStyle, SOURCE_A))
            assertEquals(Outcome.Failure(AppError.InvalidInput), store.save(badId, SOURCE_B))
            assertEquals(emptyList<StoredTrack>(), store.all())
        }

    @Test
    fun aSourceThatDoesNotMatchTheIdIsRejected() =
        runTest {
            val store = store()

            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                store.save(record(SOURCE_A), SOURCE_B),
            )
            assertEquals(emptyList<StoredTrack>(), store.all())
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
    ): StoredTrack =
        StoredTrack(
            id = TrackIdentity.of(bytes, index, count),
            name = "Track $index",
            track = trackOf(bytes.decodeToString().split('|')[index]),
            analysis = TrackAnalyzer().analyze(trackOf(bytes.decodeToString().split('|')[index])),
            style = TrackPalette.styleFor(TrackIdentity.of(bytes, index, count)),
            trackIndex = index,
        )

    private fun hashOf(bytes: ByteArray): String = TrackIdentity.documentHash(bytes)

    private fun <T> success(outcome: Outcome<T>): T =
        when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("unexpected failure: ${outcome.error}")
        }

    private companion object {
        const val SAVED_AT_MILLIS = 1_762_000_000_000L
        val SOURCE_A = "49.84000,24.03000;49.84010,24.03010".toByteArray()
        val SOURCE_B = "49.85000,24.04000;49.85010,24.04010".toByteArray()
        val SOURCE_MULTI = "49.86000,24.05000;49.86010,24.05010|49.87000,24.06000".toByteArray()
    }
}
