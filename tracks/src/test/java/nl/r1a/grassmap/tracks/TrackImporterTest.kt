package nl.r1a.grassmap.tracks

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.ElevationAccess
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

            val record = imported.tracks.single()
            assertEquals(listOf("gpx"), requested)
            assertEquals("Mock walk", record.name)
            assertEquals(2, record.analysis.pointCount)
            assertEquals(5.0, record.analysis.ascentMeters, 1e-9)
            assertEquals(64, record.id.value.length)
            assertEquals(
                listOf(ImportWarning(ImportWarningCode.InvalidTime, "trk[0]/trkseg[0]/trkpt[1]")),
                imported.warnings,
            )
            assertEquals(Outcome.Success(record), store.get(record.id))
            assertEquals(1, importer.storedTracks().size)
        }

    @Test
    fun theTrackIdIsStableForTheSameBytes() =
        runTest {
            val store = InMemoryTrackStore()
            val importer = TrackImporter(resolver(mutableListOf()) { format(importResult()) }, store)

            val first = success(importer.importFile("walk.gpx", BYTES, CancellationHandle.None))
            val second = success(importer.importFile("walk.gpx", BYTES, CancellationHandle.None))

            assertEquals(first.tracks.single().id, second.tracks.single().id)
            assertEquals(first.tracks.single().style, second.tracks.single().style)
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

    @Test
    fun aMultiTrackDocumentStoresOneRecordPerTrack() =
        runTest {
            val store = InMemoryTrackStore()
            val importer =
                TrackImporter(
                    resolver(mutableListOf()) {
                        format(
                            ImportResult(
                                entities =
                                    ImportedEntities(
                                        tracks =
                                            listOf(
                                                track("Morning"),
                                                track("Evening"),
                                            ),
                                    ),
                            ),
                        )
                    },
                    store,
                )

            val imported = success(importer.importFile("two.gpx", BYTES, CancellationHandle.None))

            assertEquals(listOf("Morning", "Evening"), imported.tracks.map { it.name })
            assertEquals(listOf(0, 1), imported.tracks.map { it.trackIndex })
            assertEquals(
                66,
                imported.tracks[0]
                    .id.value.length,
            )
            assertEquals(
                imported.tracks[0]
                    .id.value
                    .removeSuffix(":0") + ":1",
                imported.tracks[1].id.value,
            )
            assertEquals(2, store.all().size)
        }

    @Test
    fun theImporterCorrectsTheHeightsBeforeProfiling() =
        runTest {
            val store = InMemoryTrackStore()
            val importer =
                TrackImporter(
                    formats = resolver(mutableListOf()) { format(strippedResult()) },
                    store = store,
                    elevationCorrection =
                        TrackElevationCorrection(
                            ElevationAccess { position -> Outcome.Success(heightAt(position)) },
                        ),
                )

            val record = success(importer.importFile("walk.gpx", BYTES, CancellationHandle.None)).tracks.single()

            assertEquals(
                listOf(296.0, 310.0),
                record.track.segments
                    .single()
                    .points
                    .map { it.elevationMeters },
            )
            assertEquals(296.0, record.analysis.minElevationMeters ?: Double.NaN, 0.0)
            assertEquals(310.0, record.analysis.maxElevationMeters ?: Double.NaN, 0.0)
            assertEquals(ElevationSource.Dem, record.elevation?.source)
            assertEquals(1, record.elevation?.filledPoints)
            assertEquals(1, record.elevation?.keptPoints)
            assertEquals(Outcome.Success(record), store.get(record.id))
        }

    @Test
    fun aDocumentImportedWithoutAMountedDemKeepsItsHeights() =
        runTest {
            val importer =
                TrackImporter(
                    formats = resolver(mutableListOf()) { format(strippedResult()) },
                    store = InMemoryTrackStore(),
                )

            val record = success(importer.importFile("walk.gpx", BYTES, CancellationHandle.None)).tracks.single()

            assertEquals(
                listOf(296.0, null),
                record.track.segments
                    .single()
                    .points
                    .map { it.elevationMeters },
            )
            assertEquals(296.0, record.analysis.minElevationMeters ?: Double.NaN, 0.0)
            assertEquals(296.0, record.analysis.maxElevationMeters ?: Double.NaN, 0.0)
            assertEquals(ElevationSource.NoDem, record.elevation?.source)
            assertEquals(1, record.elevation?.unfilledPoints)
        }

    @Test
    fun theWarningLinesNameTheSkippedParts() =
        runTest {
            val skipped =
                listOf(
                    ImportWarning(ImportWarningCode.MissingCoordinates, "trk[0]/trkseg[0]/trkpt[2]"),
                    ImportWarning(ImportWarningCode.OutOfRangeCoordinates, "trk[0]/trkseg[0]/trkpt[4]"),
                    ImportWarning(ImportWarningCode.InvalidElevation, "trk[0]/trkseg[0]/trkpt[5]"),
                    ImportWarning(ImportWarningCode.EmptySegment, "trk[0]/trkseg[1]"),
                    ImportWarning(ImportWarningCode.EmptyTrack, "trk[1]"),
                    ImportWarning(ImportWarningCode.EmptyRoute, "rte[0]"),
                )

            assertEquals(
                listOf(
                    "trk[0]/trkseg[0]/trkpt[2]: missing coordinates",
                    "trk[0]/trkseg[0]/trkpt[4]: coordinates outside WGS84",
                    "trk[0]/trkseg[0]/trkpt[5]: unreadable elevation, point kept",
                    "trk[0]/trkseg[1]: empty segment dropped",
                    "trk[1]: empty track dropped",
                    "rte[0]: empty route dropped",
                ),
                skipped.map { warning -> warning.describe() },
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

    private fun success(outcome: Outcome<ImportReport>): ImportReport =
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

    private fun track(name: String): Track =
        Track(
            name = name,
            segments =
                listOf(
                    TrackSegment(
                        listOf(
                            TrackPoint(GeoPoint(49.84, 24.03), 296.0, 0L),
                            TrackPoint(GeoPoint(49.84001, 24.03), 301.0, null),
                        ),
                    ),
                ),
        )

    private fun heightAt(position: GeoPoint): Double = if (position.latitude > STRIPPED_LATITUDE) 310.0 else 300.0

    private fun strippedResult(): ImportResult =
        ImportResult(
            entities =
                ImportedEntities(
                    tracks =
                        listOf(
                            Track(
                                name = "Stripped",
                                segments =
                                    listOf(
                                        TrackSegment(
                                            listOf(
                                                TrackPoint(GeoPoint(49.84, 24.03), 296.0, 0L),
                                                TrackPoint(GeoPoint(49.84001, 24.03), null, null),
                                            ),
                                        ),
                                    ),
                            ),
                        ),
                ),
        )

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
        const val STRIPPED_LATITUDE = 49.84
        val BYTES = byteArrayOf(1, 2, 3, 4)
    }
}
