package nl.r1a.grassmap.plugins.gpx

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.ImportResult
import nl.r1a.grassmap.plugin.api.model.ImportedEntities
import nl.r1a.grassmap.plugin.api.model.Track
import nl.r1a.grassmap.plugin.api.model.TrackPoint
import nl.r1a.grassmap.plugin.api.model.TrackSegment
import nl.r1a.grassmap.plugin.api.model.TrackStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-0410: the writer's own acceptance. Every committed fixture is exported and
 * read again through the same `file.gpx` implementation that imported it, so a
 * change to either side cannot make the pair agree on a wrong shape.
 *
 * The schema check is [GpxSchema11]; its comment says why the official XSD is
 * not vendored.
 */
class GpxExportTest {
    @Test
    fun everyTrackFixtureRoundTripsThroughGpx11() =
        runTest {
            for (fixture in FIXTURES) {
                val imported = import(fixture)
                for (track in imported.entities.tracks) {
                    val exported = export(ImportedEntities(tracks = listOf(track)))
                    GpxSchema11.assertValid(exported)
                    val reread = parse(exported)
                    assertEquals("$fixture: track count", 1, reread.entities.tracks.size)
                    assertEquals("$fixture: track", track, reread.entities.tracks.single())
                    assertEquals("$fixture: warnings", emptyList<Any>(), reread.warnings)
                }
            }
        }

    @Test
    fun routesAndWaypointsSurviveTheRoundTrip() =
        runTest {
            for (fixture in ENTITY_FIXTURES) {
                val imported = import(fixture)
                val exported = export(imported.entities)
                GpxSchema11.assertValid(exported)
                val reread = parse(exported)
                assertEquals("$fixture: tracks", imported.entities.tracks, reread.entities.tracks)
                assertEquals("$fixture: routes", imported.entities.routes, reread.entities.routes)
                assertEquals("$fixture: waypoints", imported.entities.waypoints, reread.entities.waypoints)
                assertEquals("$fixture: warnings", emptyList<Any>(), reread.warnings)
            }
        }

    @Test
    fun aListOfTracksExportsAsOneDocument() =
        runTest {
            val imported = import("osmand-multi-track.gpx")
            assertTrue(imported.entities.tracks.size > 1)

            val exported = export(ImportedEntities(tracks = imported.entities.tracks))
            GpxSchema11.assertValid(exported)
            val reread = parse(exported)

            assertEquals(imported.entities.tracks, reread.entities.tracks)
            assertEquals(
                listOf("Morning walk", "Evening walk"),
                reread.entities.tracks.map { track -> track.name },
            )
        }

    @Test
    fun everyFixtureTrackExportsTogether() =
        runTest {
            val tracks =
                FIXTURES.flatMap { fixture ->
                    parse(bytes(fixture)).entities.tracks
                }

            val exported = export(ImportedEntities(tracks = tracks))
            GpxSchema11.assertValid(exported)
            val reread = parse(exported)

            assertEquals(tracks.size, reread.entities.tracks.size)
            assertEquals(tracks, reread.entities.tracks)
        }

    @Test
    fun aStoredStyleIsANamespacedExtensionTheImporterIgnores() =
        runTest {
            val track = import("garmin-connect-hike.gpx").entities.tracks.single()
            val styled = track.copy(style = TrackStyle(colour = "#C2185B", widthDp = 6f))

            val exported = export(ImportedEntities(tracks = listOf(styled)))
            val text = exported.toString(Charsets.UTF_8)
            assertTrue(text.contains("<grassmap:colour>#C2185B</grassmap:colour>"))
            assertTrue(text.contains("<grassmap:widthDp>6.0</grassmap:widthDp>"))
            GpxSchema11.assertValid(exported)

            val reread = parse(exported).entities.tracks.single()
            assertEquals(track.name, reread.name)
            assertEquals(track.segments, reread.segments)
            assertEquals(null, reread.style)
        }

    @Test
    fun xmlMetacharactersInNamesSurvive() =
        runTest {
            val name = "A & B <C> \"D\""
            val track =
                Track(
                    name = name,
                    segments =
                        listOf(
                            TrackSegment(
                                listOf(
                                    TrackPoint(GeoPoint(49.8, 24.0), elevationMeters = 300.0),
                                    TrackPoint(GeoPoint(49.9, 24.1), timeMillis = 1_700_000_000_000L),
                                ),
                            ),
                        ),
                )

            val exported = export(ImportedEntities(tracks = listOf(track)))
            GpxSchema11.assertValid(exported)
            assertEquals(
                name,
                parse(exported)
                    .entities.tracks
                    .single()
                    .name,
            )
        }

    @Test
    fun aDocumentTheWriterCannotRepresentIsInvalidInput() =
        runTest {
            val validPoint = TrackPoint(GeoPoint(49.8, 24.0))
            val segment = TrackSegment(listOf(validPoint))
            val invalid =
                listOf(
                    ImportedEntities(),
                    ImportedEntities(
                        tracks =
                            listOf(
                                Track(
                                    name = "far east",
                                    segments = listOf(TrackSegment(listOf(TrackPoint(GeoPoint(0.0, 180.0))))),
                                ),
                            ),
                    ),
                    ImportedEntities(
                        tracks =
                            listOf(
                                Track(
                                    name = "not a number",
                                    segments =
                                        listOf(
                                            TrackSegment(
                                                listOf(
                                                    TrackPoint(
                                                        GeoPoint(49.8, 24.0),
                                                        elevationMeters = Double.NaN,
                                                    ),
                                                ),
                                            ),
                                        ),
                                ),
                            ),
                    ),
                    ImportedEntities(tracks = listOf(Track(name = "empty segment", segments = listOf(TrackSegment(emptyList()))))),
                    ImportedEntities(tracks = listOf(Track(name = "no segments", segments = emptyList()))),
                    ImportedEntities(
                        tracks =
                            listOf(
                                Track(
                                    name = "bad style",
                                    segments = listOf(segment),
                                    style = TrackStyle(colour = "red", widthDp = 4f),
                                ),
                            ),
                    ),
                    ImportedEntities(tracks = listOf(Track(name = "bell \u0007", segments = listOf(segment)))),
                )

            for (entities in invalid) {
                assertEquals(
                    Outcome.Failure(AppError.InvalidInput),
                    exportOutcome(entities),
                )
            }
        }

    @Test
    fun aCancelledExportReturnsCancelled() =
        runTest {
            val entities =
                ImportedEntities(
                    tracks =
                        listOf(
                            Track(
                                name = "cancel",
                                segments =
                                    listOf(
                                        TrackSegment(
                                            listOf(
                                                TrackPoint(GeoPoint(49.8, 24.0)),
                                                TrackPoint(GeoPoint(49.9, 24.1)),
                                            ),
                                        ),
                                    ),
                            ),
                        ),
                )

            val result = GpxFormat().export(entities, CancellationHandle { true })

            assertEquals(Outcome.Failure(AppError.Cancelled), result)
        }

    @Test
    fun theFormatAdvertisesGpxExport() {
        val format = GpxFormat()

        assertEquals(setOf("gpx"), format.extensions)
        assertEquals(setOf("application/gpx+xml", "application/xml"), format.mimeTypes)
        assertTrue(format.entityKinds.isNotEmpty())
    }

    private suspend fun import(name: String): ImportResult =
        when (val result = GpxFormat().importFile(bytes(name), CancellationHandle.None)) {
            is Outcome.Success -> result.value
            is Outcome.Failure -> throw AssertionError("$name did not parse: ${result.error}")
        }

    private suspend fun parse(exported: ByteArray): ImportResult =
        when (val result = GpxFormat().importFile(exported, CancellationHandle.None)) {
            is Outcome.Success -> result.value
            is Outcome.Failure -> throw AssertionError("export did not parse: ${result.error}")
        }

    private suspend fun export(entities: ImportedEntities): ByteArray =
        when (val result = exportOutcome(entities)) {
            is Outcome.Success -> result.value
            is Outcome.Failure -> throw AssertionError("export failed: ${result.error}")
        }

    private suspend fun exportOutcome(entities: ImportedEntities): Outcome<ByteArray> =
        GpxFormat().export(entities, CancellationHandle.None)

    private fun bytes(name: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("tracks/$name")) {
            "missing test resource tracks/$name"
        }.use { it.readBytes() }

    private companion object {
        val FIXTURES =
            listOf(
                "osmand-walk-two-segments.gpx",
                "osmand-morning-run.gpx",
                "osmand-multi-track.gpx",
                "osmand-waypoints.gpx",
                "osmand-no-elevation.gpx",
                "osmand-no-time.gpx",
                "osmand-single-point.gpx",
                "osmand-planned-route.gpx",
                "garmin-connect-hike.gpx",
                "garmin-connect-autopause.gpx",
                "garmin-fenix-run.gpx",
                "garmin-oregon-gpx10.gpx",
                "garmin-waypoints-route.gpx",
                "strava-run.gpx",
                "strava-ride-pauses.gpx",
                "strava-no-elevation.gpx",
                "strava-route-and-track.gpx",
                "strava-ukrainian-name.gpx",
                "broken-partial.gpx",
                "broken-empty-tracks.gpx",
            )

        val ENTITY_FIXTURES =
            listOf(
                "osmand-waypoints.gpx",
                "osmand-planned-route.gpx",
                "garmin-waypoints-route.gpx",
                "strava-route-and-track.gpx",
            )
    }
}
