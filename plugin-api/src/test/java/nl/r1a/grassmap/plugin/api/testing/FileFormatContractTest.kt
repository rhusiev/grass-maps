package nl.r1a.grassmap.plugin.api.provider

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
import org.junit.Assert.assertEquals
import org.junit.Test

class FileFormatContractTest {
    @Test
    fun theContractIsTheV1FileFormat() {
        assertEquals("file.format", FileFormatContract.NAME)
        assertEquals("1.0.0", FileFormatContract.VERSION)
        assertEquals(ProviderContract("file.format", "1.0.0"), FileFormatContract.REFERENCE)
    }

    @Test
    fun aPartiallyReadDocumentCarriesEntitiesAndWarnings() =
        runTest {
            val format = PartialFormat()

            val outcome = format.importFile(byteArrayOf(1), CancellationHandle.None)

            val result =
                when (outcome) {
                    is Outcome.Success -> outcome.value
                    is Outcome.Failure -> throw AssertionError("import failed: ${outcome.error}")
                }
            assertEquals(1, result.entities.tracks.size)
            assertEquals(
                1,
                result.entities.tracks
                    .single()
                    .segments
                    .single()
                    .points.size,
            )
            assertEquals(
                listOf(ImportWarning(ImportWarningCode.EmptySegment, "trk[0]/trkseg[1]")),
                result.warnings,
            )
        }

    @Test
    fun aFormatWithoutExportReturnsUnsupported() =
        runTest {
            assertEquals(
                Outcome.Failure(AppError.Unsupported),
                PartialFormat().export(ImportedEntities(), CancellationHandle.None),
            )
        }

    private class PartialFormat : FileFormat {
        override val contract: ProviderContract = FileFormatContract.REFERENCE
        override val extensions: Set<String> = setOf("part")
        override val mimeTypes: Set<String> = setOf("application/x-part")
        override val entityKinds: Set<FileEntityKind> = setOf(FileEntityKind.Track)

        override suspend fun importFile(
            bytes: ByteArray,
            cancellation: CancellationHandle,
        ): Outcome<ImportResult> =
            Outcome.Success(
                ImportResult(
                    entities =
                        ImportedEntities(
                            tracks =
                                listOf(
                                    Track(
                                        name = "partial",
                                        segments =
                                            listOf(
                                                TrackSegment(
                                                    listOf(
                                                        TrackPoint(GeoPoint(49.84, 24.03), 296.0, 0L),
                                                    ),
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                    warnings =
                        listOf(
                            ImportWarning(ImportWarningCode.EmptySegment, "trk[0]/trkseg[1]"),
                        ),
                ),
            )
    }
}
