package nl.r1a.grassmap.tracks

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.FileEntityKind
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.ImportResult
import nl.r1a.grassmap.plugin.api.model.ImportWarning
import nl.r1a.grassmap.plugin.api.model.ImportWarningCode
import nl.r1a.grassmap.plugin.api.model.ImportedEntities
import nl.r1a.grassmap.plugin.api.model.Track
import nl.r1a.grassmap.plugin.api.model.TrackPoint
import nl.r1a.grassmap.plugin.api.model.TrackSegment
import nl.r1a.grassmap.plugin.api.provider.FileFormat
import nl.r1a.grassmap.plugin.api.provider.FileFormatContract
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackImporterTest {
    @Test
    fun aGpxIsResolvedParsedStoredAndProfiled() =
        runTest {
            val store = InMemoryTrackStore()
            val requested = mutableListOf<String>()
            val importer =
                TrackImporter(
                    formats = resolver(requested) { format(importResult()) },
                    store = store,
                )

            val imported =
                when (val outcome = importer.importFile("walk.gpx", BYTES, CancellationHandle.None)) {
                    is Outcome.Success -> outcome.value
                    is Outcome.Failure -> throw AssertionError("import failed: ${outcome.error}")
                }

            assertEquals(listOf("gpx"), requested)
            assertEquals("Mock walk", imported.record.name)
            assertEquals(2, imported.record.analysis.pointCount)
            assertEquals(5.0, imported.record.analysis.ascentMeters, 1e-9)
            assertEquals(64, imported.record.id.value.length)
            assertEquals(
                listOf(ImportWarning(ImportWarningCode.InvalidTime, "trk[0]/trkseg[0]/trkpt[1]")),
                imported.warnings,
            )
            assertEquals(Outcome.Success(imported.record), store.get(imported.record.id))
            assertEquals(1, importer.storedTracks().size)
        }

    @Test
    fun theTrackIdIsStableForTheSameBytes() =
        runTest {
            val store = InMemoryTrackStore()
            val importer = TrackImporter(resolver(mutableListOf()) { format(importResult()) }, store)

            val first = success(importer.importFile("walk.gpx", BYTES, CancellationHandle.None))
            val second = success(importer.importFile("walk.gpx", BYTES, CancellationHandle.None))

            assertEquals(first.record.id, second.record.id)
            assertEquals(1, store.all().size)
        }

    @Test
    fun aDocumentWithoutATrackIsUnsupported() =
        runTest {
            val importer =
                TrackImporter(
                    resolver(mutableListOf()) {
                        format(ImportResult(ImportedEntities()))
                    },
                    InMemoryTrackStore(),
                )

            assertEquals(
                Outcome.Failure(AppError.Unsupported),
                importer.importFile("walk.gpx", BYTES, CancellationHandle.None),
            )
        }

    @Test
    fun aFileWithoutAnExtensionIsInvalidInput() =
        runTest {
            var resolved = false
            val importer =
                TrackImporter(
                    formats = {
                        resolved = true
                        Outcome.Success(format(importResult()))
                    },
                    store = InMemoryTrackStore(),
                )

            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                importer.importFile("walk", BYTES, CancellationHandle.None),
            )
            assertEquals(false, resolved)
        }

    @Test
    fun aCancelledImportDoesNotResolveAFormat() =
        runTest {
            var resolved = false
            val importer =
                TrackImporter(
                    formats = {
                        resolved = true
                        Outcome.Success(format(importResult()))
                    },
                    store = InMemoryTrackStore(),
                )

            assertEquals(
                Outcome.Failure(AppError.Cancelled),
                importer.importFile("walk.gpx", BYTES, CancellationHandle { true }),
            )
            assertEquals(false, resolved)
        }

    @Test
    fun aMissingFormatIsATypedFailure() =
        runTest {
            val importer =
                TrackImporter(
                    formats = { Outcome.Failure(AppError.NotFound) },
                    store = InMemoryTrackStore(),
                )

            assertEquals(
                Outcome.Failure(AppError.NotFound),
                importer.importFile("walk.gpx", BYTES, CancellationHandle.None),
            )
        }

    private fun resolver(
        requested: MutableList<String>,
        format: () -> FileFormat,
    ): FileFormatResolver =
        FileFormatResolver { extension ->
            requested += extension
            Outcome.Success(format())
        }

    private fun success(outcome: Outcome<ImportedTrack>): ImportedTrack =
        when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("unexpected failure: ${outcome.error}")
        }

    private fun format(result: ImportResult): FileFormat =
        object : FileFormat {
            override val contract = FileFormatContract.REFERENCE
            override val extensions = setOf("gpx")
            override val mimeTypes = setOf("application/gpx+xml")
            override val entityKinds = setOf(FileEntityKind.Track)

            override suspend fun importFile(
                bytes: ByteArray,
                cancellation: CancellationHandle,
            ): Outcome<ImportResult> = Outcome.Success(result)
        }

    private fun importResult(): ImportResult =
        ImportResult(
            entities =
                ImportedEntities(
                    tracks =
                        listOf(
                            Track(
                                name = "Mock walk",
                                segments =
                                    listOf(
                                        TrackSegment(
                                            listOf(
                                                TrackPoint(GeoPoint(49.84, 24.03), 296.0, 0L),
                                                TrackPoint(GeoPoint(49.84001, 24.03), 301.0, null),
                                            ),
                                        ),
                                    ),
                            ),
                        ),
                ),
            warnings =
                listOf(
                    ImportWarning(ImportWarningCode.InvalidTime, "trk[0]/trkseg[0]/trkpt[1]"),
                ),
        )

    private companion object {
        val BYTES = byteArrayOf(1, 2, 3, 4)
    }
}
