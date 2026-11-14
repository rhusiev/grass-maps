package nl.r1a.grassmap.tracks

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.ElevationAccess
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.ImportedEntities
import nl.r1a.grassmap.plugin.api.model.MapTrack
import nl.r1a.grassmap.plugin.api.model.Track
import nl.r1a.grassmap.plugin.api.model.TrackPoint
import nl.r1a.grassmap.plugin.api.model.TrackSegment
import nl.r1a.grassmap.plugin.api.model.TrackStyle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun aHiddenTrackLeavesTheMapAndStaysInTheLibrary() =
        runTest {
            val store = store()
            val record = success(store.save(record(SOURCE_A), SOURCE_A))

            assertEquals(Outcome.Success(Unit), store.setHidden(record.id, true))

            assertEquals(emptyList<MapTrack>(), store.drawn.value)
            assertEquals(record.id, store.summaries().single().id)
            assertTrue(store.summaries().single().hidden)

            val reopened = store()
            assertEquals(Outcome.Success(Unit), reopened.reload())
            assertTrue(success(reopened.get(record.id)).hidden)
            assertEquals(emptyList<MapTrack>(), reopened.drawn.value)

            assertEquals(Outcome.Success(Unit), reopened.setHidden(record.id, false))
            assertEquals(listOf(record.toMapTrack()), reopened.drawn.value)
            assertFalse(success(reopened.get(record.id)).hidden)
        }

    @Test
    fun aReimportKeepsTheHiddenState() =
        runTest {
            val store = store()
            val record = success(store.save(record(SOURCE_A), SOURCE_A))
            store.setHidden(record.id, true)

            val reimported = success(store.save(record(SOURCE_A), SOURCE_A))

            assertTrue(reimported.hidden)
            assertTrue(store.summaries().single().hidden)
        }

    @Test
    fun hidingAnUnknownTrackIsNotFound() =
        runTest {
            val store = store()

            assertEquals(
                Outcome.Failure(AppError.NotFound),
                store.setHidden(TrackId("missing"), true),
            )
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

    @Test
    fun aSidecarWithoutStatisticsGetsThemOnTheNextLoad() =
        runTest {
            val source = "timed".toByteArray()
            val timedTrack =
                Track(
                    segments =
                        listOf(
                            TrackSegment(
                                listOf(
                                    TrackPoint(GeoPoint(49.84, 24.03), 100.0, 0L),
                                    TrackPoint(GeoPoint(49.8401, 24.03005), 101.0, 1_000L),
                                ),
                            ),
                        ),
                )
            val id = TrackIdentity.of(source, 0, 1)
            val record =
                StoredTrack(
                    id = id,
                    name = "Timed",
                    track = timedTrack,
                    analysis = TrackAnalyzer().analyze(timedTrack),
                    style = TrackPalette.styleFor(id),
                )
            val timedReader = TrackDocumentReader { Outcome.Success(ImportedEntities(tracks = listOf(timedTrack))) }
            success(
                FileTrackStore(
                    root = root,
                    reader = timedReader,
                    clock = { SAVED_AT_MILLIS },
                    log = { message -> logs += message },
                ).save(record, source),
            )
            val sidecar = File(root, "${id.value}.json")
            sidecar.writeText(withholdStatistics(sidecar.readText()))

            val reopened =
                FileTrackStore(
                    root = root,
                    reader = timedReader,
                    clock = { SAVED_AT_MILLIS },
                    log = { message -> logs += message },
                )
            assertEquals(Outcome.Success(Unit), reopened.reload())

            assertEquals(record.analysis, success(reopened.get(id)).analysis)
            assertEquals(record.analysis, reopened.summaries().single().analysis)
            assertTrue(sidecar.readText().contains("\"durationMillis\":1000"))
        }

    @Test
    fun aLoadedTrackIsCorrectedFromTheDemAndItsProfileFollows() =
        runTest {
            val source = "stripped".toByteArray()
            val stripped = strippedTrack()
            val id = TrackIdentity.of(source, index = 0, count = 1)
            val reader = TrackDocumentReader { Outcome.Success(ImportedEntities(tracks = listOf(stripped))) }
            val correction = TrackElevationCorrection(ElevationAccess { Outcome.Success(300.0) })
            val record =
                StoredTrack(
                    id = id,
                    name = "Stripped",
                    track = stripped,
                    analysis = TrackAnalyzer().analyze(stripped),
                    style = TrackPalette.styleFor(id),
                )
            // A save writes the document and the profile as they arrived; the
            // correction happens when a store loads them, as at app start.
            success(store(reader).save(record, source))

            val reopened =
                FileTrackStore(
                    root = root,
                    reader = reader,
                    clock = { SAVED_AT_MILLIS },
                    log = { message -> logs += message },
                    elevationCorrection = correction,
                )
            assertEquals(Outcome.Success(Unit), reopened.reload())
            val loaded = success(reopened.get(id))

            assertEquals(
                listOf(300.0, 300.0),
                loaded.track.segments
                    .single()
                    .points
                    .map { it.elevationMeters },
            )
            assertEquals(300.0, loaded.analysis.minElevationMeters ?: Double.NaN, 0.0)
            assertEquals(300.0, loaded.analysis.maxElevationMeters ?: Double.NaN, 0.0)
            assertEquals(ElevationSource.Dem, loaded.elevation?.source)
            assertEquals(2, loaded.elevation?.filledPoints)
            assertTrue(logs.any { message -> message.contains("elevation source=Dem filled=2") })
            assertEquals(loaded.analysis, reopened.summaries().single().analysis)
        }

    @Test
    fun aLoadedTrackStaysUnchangedWithoutAMountedDem() =
        runTest {
            val source = "no dem".toByteArray()
            val stripped = strippedTrack()
            val id = TrackIdentity.of(source, index = 0, count = 1)
            val reader = TrackDocumentReader { Outcome.Success(ImportedEntities(tracks = listOf(stripped))) }
            val record =
                StoredTrack(
                    id = id,
                    name = "Stripped",
                    track = stripped,
                    analysis = TrackAnalyzer().analyze(stripped),
                    style = TrackPalette.styleFor(id),
                )
            success(FileTrackStore(root = root, reader = reader, log = { message -> logs += message }).save(record, source))

            val reopened =
                FileTrackStore(
                    root = root,
                    reader = reader,
                    clock = { SAVED_AT_MILLIS },
                    log = { message -> logs += message },
                )
            assertEquals(Outcome.Success(Unit), reopened.reload())
            val loaded = success(reopened.get(id))

            assertEquals(stripped, loaded.track)
            assertNull(loaded.analysis.minElevationMeters)
            assertEquals(ElevationSource.NoDem, loaded.elevation?.source)
            assertEquals(2, loaded.elevation?.unfilledPoints)
            assertTrue(logs.none { message -> message.contains("elevation source=") })
        }

    private fun strippedTrack(): Track =
        Track(
            segments =
                listOf(
                    TrackSegment(
                        listOf(
                            TrackPoint(GeoPoint(49.84, 24.03)),
                            TrackPoint(GeoPoint(49.8401, 24.03005)),
                        ),
                    ),
                ),
        )

    private fun withholdStatistics(text: String): String {
        val root = Json.parseToJsonElement(text).jsonObject
        val analysis = root.getValue("analysis").jsonObject
        val kept =
            buildJsonObject {
                root.forEach { (key, value) ->
                    if (key != "analysis") {
                        put(key, value)
                    }
                }
                put(
                    "analysis",
                    buildJsonObject {
                        analysis.forEach { (key, value) ->
                            if (key !in STATISTICS_KEYS) {
                                put(key, value)
                            }
                        }
                    },
                )
            }
        return kept.toString()
    }

    private fun store(): FileTrackStore = store(reader())

    private fun store(reader: TrackDocumentReader): FileTrackStore =
        FileTrackStore(
            root = root,
            reader = reader,
            clock = { SAVED_AT_MILLIS },
            log = { message -> logs += message },
        )

    private fun reader(): TrackDocumentReader =
        TrackDocumentReader { bytes ->
            val text = bytes.decodeToString()
            if (text.startsWith("bad")) {
                Outcome.Failure(AppError.CorruptData)
            } else {
                Outcome.Success(ImportedEntities(tracks = text.split('|').map { part -> trackOf(part) }))
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
        val STATISTICS_KEYS =
            setOf(
                "durationMillis",
                "movingTimeMillis",
                "averageSpeedMetersPerSecond",
                "maxSpeedMetersPerSecond",
            )
    }
}
