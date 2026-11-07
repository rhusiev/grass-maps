package nl.r1a.grassmap.plugins.gpx

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.FileEntityKind
import nl.r1a.grassmap.plugin.api.model.ImportResult
import nl.r1a.grassmap.plugin.api.model.ImportWarning
import nl.r1a.grassmap.plugin.api.model.ImportWarningCode
import nl.r1a.grassmap.plugin.api.model.ImportedEntities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxParserTest {
    @Test
    fun theMockWalkImportsEveryPoint() =
        runTest {
            val result = import(fixture())

            assertEquals(emptyList<ImportWarning>(), result.warnings)
            val track = result.entities.tracks.single()
            assertEquals("Mock walk around Lviv", track.name)
            val points = track.segments.single().points
            assertEquals(FIXTURE_POINTS, points.size)
            assertEquals(49.8419, points.first().position.latitude, 0.0)
            assertEquals(24.0316, points.first().position.longitude, 0.0)
            assertEquals(296.0, points.first().elevationMeters ?: 0.0, 0.0)
            assertEquals(FIRST_TIME_MILLIS, points.first().timeMillis)
        }

    @Test
    fun aPartlyBrokenDocumentKeepsWhatItCanRead() =
        runTest {
            val result = import(PARTLY_BROKEN.toByteArray())

            val track = result.entities.tracks.single()
            val segments = track.segments
            assertEquals(1, segments.size)
            assertEquals(2, segments.single().points.size)
            assertNull(segments.single().points[0].elevationMeters)
            assertNull(segments.single().points[0].timeMillis)

            val route = result.entities.routes.single()
            assertEquals("Ridge", route.name)
            assertEquals(2, route.points.size)
            assertEquals(1, result.entities.waypoints.size)

            assertEquals(
                setOf(
                    ImportWarningCode.MissingCoordinates,
                    ImportWarningCode.InvalidElevation,
                    ImportWarningCode.InvalidTime,
                    ImportWarningCode.EmptySegment,
                ),
                result.warnings.map { it.code }.toSet(),
            )
            assertTrue(result.warnings.contains(ImportWarning(ImportWarningCode.MissingCoordinates, "trk[0]/trkseg[0]/trkpt[2]")))
            assertTrue(result.warnings.contains(ImportWarning(ImportWarningCode.EmptySegment, "trk[0]/trkseg[1]")))
        }

    @Test
    fun prefixedGpxOneZeroElementsAreRead() =
        runTest {
            val result = import(PREFIXED.toByteArray())

            assertEquals(1, result.entities.tracks.size)
            assertEquals(
                1,
                result.entities.tracks
                    .single()
                    .segments
                    .single()
                    .points.size,
            )
        }

    @Test
    fun malformedXmlIsATypedFailure() =
        runTest {
            assertEquals(
                Outcome.Failure(AppError.CorruptData),
                GpxFormat().importFile("<gpx><trk>".toByteArray(), CancellationHandle.None),
            )
        }

    @Test
    fun aDoctypeWithAnExternalEntityIsRejectedBeforeParsing() =
        runTest {
            val outcome = GpxFormat().importFile(EXTERNAL_ENTITY.toByteArray(), CancellationHandle.None)

            // A failure carries no entities, so the referenced file cannot
            // surface as a track name.
            assertEquals(Outcome.Failure(AppError.CorruptData), outcome)
        }

    @Test
    fun anEntityDeclarationWithoutADoctypeIsRejected() =
        runTest {
            assertEquals(
                Outcome.Failure(AppError.CorruptData),
                GpxFormat().importFile(INTERNAL_ENTITY.toByteArray(), CancellationHandle.None),
            )
        }

    @Test
    fun aUtf16DoctypeIsAlsoRejected() =
        runTest {
            assertEquals(
                Outcome.Failure(AppError.CorruptData),
                GpxFormat().importFile(
                    UTF16_EXTERNAL_ENTITY.toByteArray(Charsets.UTF_16LE),
                    CancellationHandle.None,
                ),
            )
        }

    @Test
    fun aNonGpxRootIsATypedFailure() =
        runTest {
            assertEquals(
                Outcome.Failure(AppError.CorruptData),
                GpxFormat().importFile("<kml/>".toByteArray(), CancellationHandle.None),
            )
        }

    @Test
    fun aCancelledImportDoesNoWork() =
        runTest {
            assertEquals(
                Outcome.Failure(AppError.Cancelled),
                GpxFormat().importFile(fixture(), CancellationHandle { true }),
            )
        }

    @Test
    fun exportIsUnsupportedUntilTheExportTask() =
        runTest {
            assertEquals(
                Outcome.Failure(AppError.Unsupported),
                GpxFormat().export(ImportedEntities(), CancellationHandle.None),
            )
        }

    @Test
    fun theFormatDeclaresItsStaticCapabilities() {
        val format = GpxFormat()

        assertEquals(setOf("gpx"), format.extensions)
        assertEquals(setOf("application/gpx+xml", "application/xml"), format.mimeTypes)
        assertEquals(
            setOf(FileEntityKind.Track, FileEntityKind.Route, FileEntityKind.Waypoint),
            format.entityKinds,
        )
    }

    private suspend fun import(bytes: ByteArray): ImportResult =
        when (val outcome = GpxFormat().importFile(bytes, CancellationHandle.None)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("import failed: ${outcome.error}")
        }

    private fun fixture(): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(FIXTURE)) {
            "missing test resource $FIXTURE"
        }.use { it.readBytes() }

    private companion object {
        const val FIXTURE = "location/mock-walk.gpx"
        const val FIXTURE_POINTS = 60
        const val FIRST_TIME_MILLIS = 1_794_042_000_000L

        val PARTLY_BROKEN =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.0" creator="test" xmlns="http://www.topografix.com/GPX/1/0">
              <trk>
                <name>Broken</name>
                <trkseg>
                  <trkpt lat="49.84" lon="24.03"><ele>n/a</ele><time>not-a-time</time></trkpt>
                  <trkpt lat="49.841" lon="24.031"><ele>300</ele></trkpt>
                  <trkpt lon="24.032"/>
                </trkseg>
                <trkseg/>
              </trk>
              <rte>
                <name>Ridge</name>
                <rtept lat="49.85" lon="24.05"><ele>400</ele></rtept>
                <rtept lat="49.86" lon="24.06"/>
              </rte>
              <wpt lat="49.87" lon="24.07"><name>Spring</name><ele>350</ele></wpt>
            </gpx>
            """.trimIndent()

        val PREFIXED =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <g:gpx version="1.0" creator="test" xmlns:g="http://www.topografix.com/GPX/1/0">
              <g:trk>
                <g:name>Prefixed</g:name>
                <g:trkseg>
                  <g:trkpt lat="49.84" lon="24.03"><g:ele>296</g:ele></g:trkpt>
                </g:trkseg>
              </g:trk>
            </g:gpx>
            """.trimIndent()

        val EXTERNAL_ENTITY =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE gpx [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
              <trk>
                <name>&xxe;</name>
                <trkseg>
                  <trkpt lat="49.84" lon="24.03"><ele>296</ele></trkpt>
                </trkseg>
              </trk>
            </gpx>
            """.trimIndent()

        val INTERNAL_ENTITY =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!ENTITY xxe "expanded">
            <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><name>&xxe;</name></trk>
            </gpx>
            """.trimIndent()

        val UTF16_EXTERNAL_ENTITY =
            """
            <?xml version="1.0" encoding="UTF-16"?>
            <!DOCTYPE gpx [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><name>&xxe;</name></trk>
            </gpx>
            """.trimIndent()
    }
}
