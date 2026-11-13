package nl.r1a.grassmap.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.app.generated.GeneratedPlugins
import nl.r1a.grassmap.core.PluginHost
import nl.r1a.grassmap.core.ProviderRegistry
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.TrackStyle
import nl.r1a.grassmap.tracks.ImportReport
import nl.r1a.grassmap.tracks.TrackId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The T-0401 acceptance: every committed fixture imports through the real
 * `file.gpx` provider and the file store, and the profile matches the authored
 * geometry.
 *
 * The expected profile does not come from the production analyser. Point counts
 * and ascent/descent follow from the authored elevation sequence, one step per
 * point, and were printed by the generator recorded in
 * `test-data/tracks/README.md`. A segment boundary is not a step, which the
 * multi-segment fixtures exercise.
 */
class TrackFixturesTest {
    private val root: File = Files.createTempDirectory("grass-tracks").toFile()

    @After
    fun deleteStore() {
        root.deleteRecursively()
    }

    @Test
    fun everyCommittedFixtureImportsWithItsExpectedProfile() =
        runTest {
            val access = access()

            for (fixture in FIXTURES) {
                val report = import(access, fixture.file)
                assertEquals("${fixture.file}: track count", fixture.tracks, report.tracks.size)
                assertEquals(
                    "${fixture.file}: segment count",
                    fixture.segments,
                    report.tracks.sumOf { record -> record.track.segments.size },
                )
                assertEquals(
                    "${fixture.file}: point count",
                    fixture.points,
                    report.tracks.sumOf { record -> record.analysis.pointCount },
                )
                assertEquals(
                    "${fixture.file}: ascent",
                    fixture.ascent,
                    report.tracks.sumOf { record -> record.analysis.ascentMeters },
                    ELEVATION_TOLERANCE,
                )
                assertEquals(
                    "${fixture.file}: descent",
                    fixture.descent,
                    report.tracks.sumOf { record -> record.analysis.descentMeters },
                    ELEVATION_TOLERANCE,
                )
                assertEquals(
                    "${fixture.file}: minimum elevation",
                    fixture.minElevation,
                    report.tracks.mapNotNull { record -> record.analysis.minElevationMeters }.minOrNull(),
                )
                assertEquals(
                    "${fixture.file}: maximum elevation",
                    fixture.maxElevation,
                    report.tracks.mapNotNull { record -> record.analysis.maxElevationMeters }.maxOrNull(),
                )
                assertEquals("${fixture.file}: warnings", fixture.warnings, report.warnings.size)
                if (fixture.file != SINGLE_POINT) {
                    assertTrue(
                        "${fixture.file}: every track is drawable",
                        report.tracks.all { record -> record.toMapTrack().segments.any { it.size >= 2 } },
                    )
                }
            }

            assertEquals(RECORD_COUNT, access.storedTracks().size)
            assertEquals(RECORD_COUNT, access.drawn.value.size)
        }

    @Test
    fun theMultiTrackFixtureStoresOneRecordPerTrack() =
        runTest {
            val report = import(access(), "osmand-multi-track.gpx")

            assertEquals(
                listOf("Morning walk", "Evening walk"),
                report.tracks.map { it.name },
            )
            assertEquals(listOf(0, 1), report.tracks.map { it.trackIndex })
            assertTrue(
                report.tracks[0]
                    .id.value
                    .endsWith(":0"),
            )
            assertTrue(
                report.tracks[1]
                    .id.value
                    .endsWith(":1"),
            )
            assertEquals(
                3,
                report.tracks[1]
                    .track.segments
                    .single()
                    .points.size,
            )
        }

    @Test
    fun theBrokenFixtureImportsItsReadablePartAndNamesEverySkippedPart() =
        runTest {
            val report = import(access(), "broken-partial.gpx")

            val record = report.tracks.single()
            assertEquals(4, record.analysis.pointCount)
            assertEquals(1.0, record.analysis.ascentMeters, ELEVATION_TOLERANCE)
            assertEquals(0.0, record.analysis.descentMeters, ELEVATION_TOLERANCE)
            assertEquals(460.0, record.analysis.minElevationMeters ?: 0.0, 0.0)
            assertEquals(463.0, record.analysis.maxElevationMeters ?: 0.0, 0.0)
            val points =
                record.track.segments
                    .single()
                    .points
            assertNull(points[2].elevationMeters)
            assertNull(points[2].timeMillis)
            assertEquals(
                listOf(
                    "trk[0]/trkseg[0]/trkpt[2]: missing coordinates",
                    "trk[0]/trkseg[0]/trkpt[3]: missing coordinates",
                    "trk[0]/trkseg[0]/trkpt[4]: coordinates outside WGS84",
                    "trk[0]/trkseg[0]/trkpt[5]: unreadable elevation, point kept",
                    "trk[0]/trkseg[0]/trkpt[5]: unreadable time, point kept",
                    "trk[0]/trkseg[1]: empty segment dropped",
                    "trk[1]/trkseg[0]: empty segment dropped",
                    "trk[1]: empty track dropped",
                ),
                report.warningLines(),
            )
        }

    @Test
    fun theEmptyTrackFixtureKeepsTheReadableSegmentsAndNamesTheRest() =
        runTest {
            val report = import(access(), "broken-empty-tracks.gpx")

            val record = report.tracks.single()
            assertEquals(2, record.track.segments.size)
            assertEquals(listOf(3, 1), record.track.segments.map { it.points.size })
            assertEquals(4, record.analysis.pointCount)
            assertEquals(2.0, record.analysis.ascentMeters, ELEVATION_TOLERANCE)
            assertEquals(470.0, record.analysis.minElevationMeters ?: 0.0, 0.0)
            assertEquals(474.0, record.analysis.maxElevationMeters ?: 0.0, 0.0)
            assertEquals(
                listOf(
                    "trk[0]: empty track dropped",
                    "trk[1]/trkseg[0]: empty segment dropped",
                    "trk[1]/trkseg[2]/trkpt[0]: missing coordinates",
                ),
                report.warningLines(),
            )
        }

    @Test
    fun aReloadedStoreReturnsTheSameTracksAndProfiles() =
        runTest {
            val first = access()
            for (fixture in FIXTURES) {
                import(first, fixture.file)
            }
            val before = first.storedTracks().associateBy { it.id }
            val expected = before.values.single { record -> record.name == "Lviv centre walk" }

            val reopened = access()
            assertEquals(Outcome.Success(Unit), reopened.reload())

            val after = reopened.storedTracks()
            assertEquals(RECORD_COUNT, after.size)
            assertEquals(before.keys, after.map { it.id }.toSet())
            val actual = after.single { record -> record.id == expected.id }
            assertEquals(expected.name, actual.name)
            assertEquals(expected.analysis, actual.analysis)
            assertEquals(expected.style, actual.style)
            assertEquals(expected.track, actual.track)
            assertEquals(RECORD_COUNT, reopened.drawn.value.size)
        }

    @Test
    fun reimportingAFileKeepsOneRecordAndItsColour() =
        runTest {
            val access = access()
            val first = import(access, "garmin-connect-hike.gpx").tracks.single()
            val recoloured = TrackStyle(colour = "#C2185B", widthDp = 6f)
            assertEquals(Outcome.Success(Unit), access.setStyle(first.id, recoloured))

            val second = import(access, "garmin-connect-hike.gpx").tracks.single()

            assertEquals(first.id, second.id)
            assertEquals(1, access.storedTracks().size)
            assertEquals(recoloured, second.style)
            assertEquals(recoloured, storedStyle(access, second.id))
        }

    @Test
    fun everyTimedFixtureHasItsExpectedStatistics() =
        runTest {
            val access = access()

            for (row in STATISTICS) {
                val report = import(access, row.file)
                val record = report.tracks.single { track -> track.trackIndex == row.trackIndex }
                val analysis = record.analysis
                assertEquals("${row.file}: duration", row.durationMillis, analysis.durationMillis)
                assertEquals("${row.file}: moving time", row.movingMillis, analysis.movingTimeMillis)
                if (row.averageSpeed == null) {
                    assertNull("${row.file}: average speed", analysis.averageSpeedMetersPerSecond)
                } else {
                    assertEquals(
                        "${row.file}: average speed",
                        row.averageSpeed,
                        analysis.averageSpeedMetersPerSecond ?: Double.NaN,
                        SPEED_TOLERANCE,
                    )
                }
                if (row.maxSpeed == null) {
                    assertNull("${row.file}: maximum speed", analysis.maxSpeedMetersPerSecond)
                } else {
                    assertEquals(
                        "${row.file}: maximum speed",
                        row.maxSpeed,
                        analysis.maxSpeedMetersPerSecond ?: Double.NaN,
                        SPEED_TOLERANCE,
                    )
                }
            }
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
            tracksRoot = root,
        )

    private suspend fun storedStyle(
        access: TrackAccess,
        id: TrackId,
    ): TrackStyle =
        when (val outcome = access.stored(id)) {
            is Outcome.Success -> outcome.value.style
            is Outcome.Failure -> throw AssertionError("stored track missing: ${outcome.error}")
        }

    private suspend fun import(
        access: TrackAccess,
        file: String,
    ): ImportReport =
        when (
            val outcome =
                access.import(
                    fileName = file,
                    bytes = bytes("tracks/$file"),
                    cancellation = CancellationHandle.None,
                )
        ) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("$file did not import: ${outcome.error}")
        }

    private fun bytes(resource: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(resource)) {
            "missing test resource $resource"
        }.use { it.readBytes() }

    private data class FixtureExpectation(
        val file: String,
        val tracks: Int,
        val segments: Int,
        val points: Int,
        val ascent: Double,
        val descent: Double,
        val minElevation: Double?,
        val maxElevation: Double?,
        val warnings: Int = 0,
    )

    private data class StatisticsExpectation(
        val file: String,
        val trackIndex: Int,
        val durationMillis: Long?,
        val movingMillis: Long?,
        val averageSpeed: Double?,
        val maxSpeed: Double?,
    )

    private companion object {
        const val ELEVATION_TOLERANCE = 1e-6
        const val SPEED_TOLERANCE = 1e-6
        const val SINGLE_POINT = "osmand-single-point.gpx"
        const val RECORD_COUNT = 24

        /** Printed by `test-data/tracks/generate-fixtures.py`, one row per track. */
        val STATISTICS =
            listOf(
                StatisticsExpectation("osmand-walk-two-segments.gpx", 0, 123_000, 7_000, 11.683311, 11.683324),
                StatisticsExpectation("osmand-morning-run.gpx", 0, 5_000, 5_000, 11.683276, 11.683280),
                StatisticsExpectation("osmand-multi-track.gpx", 0, 3_000, 3_000, 11.683255, 11.683258),
                StatisticsExpectation("osmand-multi-track.gpx", 1, 2_000, 2_000, 11.683234, 11.683235),
                StatisticsExpectation("osmand-waypoints.gpx", 0, 3_000, 3_000, 11.683210, 11.683212),
                StatisticsExpectation("osmand-no-elevation.gpx", 0, 4_000, 4_000, 11.683163, 11.683167),
                StatisticsExpectation("osmand-no-time.gpx", 0, null, null, null, null),
                StatisticsExpectation("osmand-single-point.gpx", 0, null, null, null, null),
                StatisticsExpectation("osmand-planned-route.gpx", 0, 2_000, 2_000, 11.683097, 11.683098),
                StatisticsExpectation("garmin-connect-hike.gpx", 0, 5_000, 5_000, 11.683071, 11.683076),
                StatisticsExpectation("garmin-connect-autopause.gpx", 0, 603_000, 9_000, 11.683017, 11.683053),
                StatisticsExpectation("garmin-fenix-run.gpx", 0, 604_000, 9_000, 11.682938, 11.682962),
                StatisticsExpectation("garmin-oregon-gpx10.gpx", 0, 3_000, 3_000, 11.682891, 11.682894),
                StatisticsExpectation("garmin-waypoints-route.gpx", 0, 2_000, 2_000, 11.682870, 11.682871),
                StatisticsExpectation("strava-run.gpx", 0, 7_000, 7_000, 11.682841, 11.682848),
                StatisticsExpectation("strava-ride-pauses.gpx", 0, 722_000, 12_000, 11.682780, 11.682825),
                StatisticsExpectation("strava-no-elevation.gpx", 0, 4_000, 4_000, 11.682708, 11.682712),
                StatisticsExpectation("strava-route-and-track.gpx", 0, 3_000, 3_000, 11.682686, 11.682689),
                StatisticsExpectation("strava-ukrainian-name.gpx", 0, 3_000, 3_000, 11.682664, 11.682666),
            )

        val FIXTURES =
            listOf(
                FixtureExpectation("osmand-walk-two-segments.gpx", 1, 2, 9, 4.0, 4.0, 300.0, 304.0),
                FixtureExpectation("osmand-morning-run.gpx", 1, 1, 6, 8.5, 2.5, 310.0, 316.0),
                FixtureExpectation("osmand-multi-track.gpx", 2, 2, 7, 3.0, 3.0, 320.0, 323.0),
                FixtureExpectation("osmand-waypoints.gpx", 1, 1, 4, 3.0, 0.0, 330.0, 333.0),
                FixtureExpectation("osmand-no-elevation.gpx", 1, 1, 5, 0.0, 0.0, null, null),
                FixtureExpectation("osmand-no-time.gpx", 1, 1, 4, 3.0, 0.0, 340.0, 343.0),
                FixtureExpectation("osmand-single-point.gpx", 1, 1, 1, 0.0, 0.0, 350.0, 350.0),
                FixtureExpectation("osmand-planned-route.gpx", 2, 2, 6, 12.0, 0.0, 360.0, 370.0),
                FixtureExpectation("garmin-connect-hike.gpx", 1, 1, 6, 11.5, 3.5, 370.0, 378.0),
                FixtureExpectation("garmin-connect-autopause.gpx", 1, 3, 12, 10.0, 2.0, 380.0, 388.0),
                FixtureExpectation("garmin-fenix-run.gpx", 1, 2, 11, 7.0, 7.0, 390.0, 396.0),
                FixtureExpectation("garmin-oregon-gpx10.gpx", 1, 1, 4, 4.0, 1.0, 400.0, 403.0),
                FixtureExpectation("garmin-waypoints-route.gpx", 2, 2, 5, 7.0, 0.0, 410.0, 415.0),
                FixtureExpectation("strava-run.gpx", 1, 1, 8, 8.0, 3.0, 420.0, 426.0),
                FixtureExpectation("strava-ride-pauses.gpx", 1, 4, 16, 8.0, 7.0, 430.0, 438.0),
                FixtureExpectation("strava-no-elevation.gpx", 1, 1, 5, 0.0, 0.0, null, null),
                FixtureExpectation("strava-route-and-track.gpx", 2, 2, 7, 14.0, 0.0, 440.0, 450.0),
                FixtureExpectation("strava-ukrainian-name.gpx", 1, 1, 4, 2.0, 1.0, 450.0, 452.0),
                FixtureExpectation("broken-partial.gpx", 1, 1, 4, 1.0, 0.0, 460.0, 463.0, warnings = 8),
                FixtureExpectation("broken-empty-tracks.gpx", 1, 2, 4, 2.0, 0.0, 470.0, 474.0, warnings = 3),
            )
    }
}
