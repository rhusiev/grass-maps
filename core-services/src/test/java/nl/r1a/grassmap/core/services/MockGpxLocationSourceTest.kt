package nl.r1a.grassmap.core.services

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockGpxLocationSourceTest {
    @Test
    fun theFixtureParsesInDocumentOrder() =
        runTest {
            val source = source()
            val fixes = source.fixes.toList()

            assertEquals(FIXTURE_POINTS, fixes.size)
            assertEquals(GeoPoint(49.841900, 24.031600), fixes.first()?.position)
            assertEquals(296.0, fixes.first()?.altitudeMeters ?: 0.0, 0.001)
            assertEquals(FIRST_FIX_TIME_MILLIS, fixes.first()?.timeMillis)
            assertEquals(DEFAULT_ACCURACY_METERS, fixes.first()?.accuracyMeters ?: 0.0, 0.0)
        }

    @Test
    fun replayWaitsOneTickBetweenFixes() =
        runTest {
            val delays = mutableListOf<Long>()
            val source = source(delayMillis = { delays += it })
            val fixes = source.fixes.toList()

            assertEquals(FIXTURE_POINTS - 1, delays.size)
            assertTrue(delays.all { it == MockGpxLocationSource.DEFAULT_TICK_MILLIS })
            assertEquals(49.841900, fixes.first()?.position?.latitude ?: 0.0, 0.0)
            assertTrue(fixes[1]?.position != fixes[0]?.position)
        }

    @Test
    fun stopAtEndCompletesAfterTheLastFix() =
        runTest {
            val source = source(loop = false)
            val fixes = source.fixes.toList()

            assertEquals(FIXTURE_POINTS, fixes.size)
        }

    @Test
    fun loopStartsAgainAtTheFirstFixAfterOneTick() =
        runTest {
            val delays = mutableListOf<Long>()
            val source = source(loop = true, delayMillis = { delays += it })
            val fixes = source.fixes.take(FIXTURE_POINTS + 1).toList()

            assertEquals(fixes[0]?.position, fixes[FIXTURE_POINTS]?.position)
            assertEquals(FIXTURE_POINTS, delays.size)
        }

    @Test
    fun aPointWithoutATimeGetsTheTickFallback() =
        runTest {
            val gpx =
                """
                <gpx><trk><trkseg>
                  <trkpt lat="49.84" lon="24.03"><ele>300</ele></trkpt>
                  <trkpt lat="49.85" lon="24.04"/>
                </trkseg></trk></gpx>
                """.trimIndent()

            val parsed = parsed(gpx)

            assertEquals(1_000L, parsed[1].timeMillis - parsed[0].timeMillis)
            assertTrue("fallback times must be current, not 1970", parsed[0].timeMillis > 0L)
        }

    @Test
    fun anOutOfRangePointIsSkipped() =
        runTest {
            val gpx =
                """
                <gpx><trk><trkseg>
                  <trkpt lat="91.0" lon="24.03"/>
                  <trkpt lat="49.85" lon="24.04"/>
                </trkseg></trk></gpx>
                """.trimIndent()

            val parsed = parsed(gpx)

            assertEquals(1, parsed.size)
            assertEquals(49.85, parsed.single().position.latitude, 0.0)
        }

    @Test
    fun malformedXmlIsAFailureValue() {
        assertEquals(Outcome.Failure(AppError.CorruptData), MockGpxLocationSource.fromGpx("<gpx><trk>"))
    }

    @Test
    fun aDocumentWithoutTrackPointsIsAFailureValue() {
        assertEquals(
            Outcome.Failure(AppError.CorruptData),
            MockGpxLocationSource.fromGpx("<gpx><trk><trkseg/></trk></gpx>"),
        )
        assertEquals(Outcome.Failure(AppError.CorruptData), MockGpxLocationSource.fromGpx("  "))
    }

    @Test
    fun aFailedLoaderEmitsNoFixAndCompletes() =
        runTest {
            val source =
                MockGpxLocationSource.fromLoader(
                    loadGpx = { Outcome.Failure(AppError.CorruptData) },
                )

            assertEquals(listOf(null), source.fixes.toList())
        }

    private fun source(
        loop: Boolean = false,
        delayMillis: suspend (Long) -> Unit = {},
    ): MockGpxLocationSource {
        val outcome = MockGpxLocationSource.fromGpx(fixture(), loop = loop, delayMillis = delayMillis)
        return (outcome as Outcome.Success<MockGpxLocationSource>).value
    }

    private suspend fun parsed(gpx: String): List<LocationFix> {
        val outcome = MockGpxLocationSource.fromGpx(gpx)
        val source = (outcome as Outcome.Success<MockGpxLocationSource>).value
        return source.fixes.toList().filterNotNull()
    }

    private fun fixture(): String = requireNotNull(javaClass.getResource(FIXTURE_RESOURCE)) { "missing $FIXTURE_RESOURCE" }.readText()

    private companion object {
        const val FIXTURE_RESOURCE = "/location/mock-walk.gpx"
        const val FIXTURE_POINTS = 60
        const val FIRST_FIX_TIME_MILLIS = 1_794_042_000_000L
    }
}
