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
import nl.r1a.grassmap.plugin.api.model.TrackRoute
import nl.r1a.grassmap.plugin.api.model.TrackSegment
import nl.r1a.grassmap.plugin.api.model.TrackWaypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-0411 at the document level: a route is a `<rte>` with one `<rtept>` per
 * point and never a `<trk>`, a waypoint keeps its name, description and
 * coordinates through a round trip, and one document may hold both elements.
 *
 * Every exported document goes through [GpxSchema11], so the shape is checked
 * against the schema's element order, not just against the parser.
 */
class GpxRouteWaypointTest {
    @Test
    fun aRouteIsWrittenAsAnRteWithOnePointPerVertex() =
        runTest {
            val route =
                TrackRoute(
                    name = "Kyivka loop",
                    points =
                        listOf(
                            TrackPoint(GeoPoint(49.8400, 24.0300), elevationMeters = 320.0),
                            TrackPoint(GeoPoint(49.8405, 24.0310)),
                            TrackPoint(GeoPoint(49.8410, 24.0320), elevationMeters = 340.0),
                        ),
                )

            val exported = export(ImportedEntities(routes = listOf(route)))
            GpxSchema11.assertValid(exported)
            val text = exported.toString(Charsets.UTF_8)

            assertTrue("the route is an rte", text.contains("<rte>"))
            assertEquals("one point per vertex", 3, Regex("<rtept ").findAll(text).count())
            assertFalse("a route is never a trk", text.contains("<trk"))

            assertEquals(route, parse(exported).entities.routes.single())
        }

    @Test
    fun aRouteWithOnePointIsAValidDocument() =
        runTest {
            val route = TrackRoute(name = "One stop", points = listOf(TrackPoint(GeoPoint(49.84, 24.03))))

            val exported = export(ImportedEntities(routes = listOf(route)))
            GpxSchema11.assertValid(exported)

            val reread = parse(exported).entities.routes.single()
            assertEquals(1, reread.points.size)
            assertEquals(GeoPoint(49.84, 24.03), reread.points.single().position)
        }

    @Test
    fun aWaypointKeepsItsNameDescriptionAndCoordinates() =
        runTest {
            val waypoint =
                TrackWaypoint(
                    name = "Spring",
                    position = GeoPoint(49.8605, 24.0515),
                    elevationMeters = 412.0,
                    description = "Drinking water, may run dry\nin August",
                )

            val exported = export(ImportedEntities(waypoints = listOf(waypoint)))
            GpxSchema11.assertValid(exported)

            val reread = parse(exported).entities.waypoints.single()
            assertEquals(waypoint, reread)
            assertEquals("Drinking water, may run dry\nin August", reread.description)
        }

    @Test
    fun aWaypointSymbolAndTypeAreDroppedBecauseThisBuildHasNoIconVocabulary() =
        runTest {
            // garmin-waypoints-route.gpx carries <sym> on both waypoints.
            val imported = parse(bytes("garmin-waypoints-route.gpx")).entities.waypoints
            assertEquals(listOf("Trailhead", "Spring"), imported.map { waypoint -> waypoint.name })

            val exported = export(ImportedEntities(waypoints = imported))
            GpxSchema11.assertValid(exported)
            val text = exported.toString(Charsets.UTF_8)
            assertFalse("no symbol survives", text.contains("<sym>"))
            assertFalse("no type survives", text.contains("<type>"))
            assertEquals(imported, parse(exported).entities.waypoints)
        }

    @Test
    fun aDocumentWithARouteAndATrackKeepsBoth() =
        runTest {
            val track =
                Track(
                    name = "Garmin walk",
                    segments =
                        listOf(
                            TrackSegment(
                                listOf(
                                    TrackPoint(GeoPoint(49.8600, 24.0510)),
                                    TrackPoint(GeoPoint(49.8602, 24.0511)),
                                ),
                            ),
                        ),
                )
            val route =
                TrackRoute(
                    name = "Garmin route",
                    points = listOf(TrackPoint(GeoPoint(49.86, 24.051)), TrackPoint(GeoPoint(49.861, 24.052))),
                )

            val exported = export(ImportedEntities(tracks = listOf(track), routes = listOf(route)))
            GpxSchema11.assertValid(exported)

            // The schema's own order is wpt, rte, trk; the parser reads each
            // family on its own, so one document yields one of each.
            val text = exported.toString(Charsets.UTF_8)
            assertTrue(text.indexOf("<rte>") < text.indexOf("<trk>"))

            val reread = parse(exported).entities
            assertEquals(listOf(track), reread.tracks)
            assertEquals(listOf(route), reread.routes)
        }

    @Test
    fun aDescriptionIsRefusedWhenItCarriesAControlCharacter() =
        runTest {
            val waypoint =
                TrackWaypoint(
                    name = "Spring",
                    position = GeoPoint(49.86, 24.05),
                    description = "water\u0000here",
                )

            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                GpxFormat().export(ImportedEntities(waypoints = listOf(waypoint)), CancellationHandle.None),
            )
        }

    private suspend fun parse(exported: ByteArray): ImportResult =
        when (val result = GpxFormat().importFile(exported, CancellationHandle.None)) {
            is Outcome.Success -> result.value
            is Outcome.Failure -> throw AssertionError("export did not parse: ${result.error}")
        }

    private suspend fun export(entities: ImportedEntities): ByteArray =
        when (val result = GpxFormat().export(entities, CancellationHandle.None)) {
            is Outcome.Success -> result.value
            is Outcome.Failure -> throw AssertionError("export failed: ${result.error}")
        }

    private fun bytes(name: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("tracks/$name")) {
            "missing test resource tracks/$name"
        }.use { it.readBytes() }
}
