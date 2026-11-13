package nl.r1a.grassmap.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.app.generated.GeneratedPlugins
import nl.r1a.grassmap.core.PluginHost
import nl.r1a.grassmap.core.ProviderRegistry
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.PlatformFile
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanOption
import nl.r1a.grassmap.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmap.plugin.api.model.TrackKind
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteOptions
import nl.r1a.grassmap.plugin.api.provider.RoutePreference
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.tracks.ImportReport
import nl.r1a.grassmap.tracks.PlannedRouteEngine
import nl.r1a.grassmap.tracks.RecomputedRoute
import nl.r1a.grassmap.tracks.RouteGeometrySource
import nl.r1a.grassmap.tracks.StoredTrack
import nl.r1a.grassmap.tracks.TrackId
import nl.r1a.grassmap.tracks.storedGeometry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * T-0411 through the real seams: a route the planner published is saved as a
 * GPX `<rte>` by the real `file.gpx` provider, its recompute sidecar is written
 * beside it, the library reloads both, and an export writes a `<wpt>` back.
 *
 * The routing engine is a recording fake. No test here reaches a network or a
 * tile pack, and the no-engine case is a state the library is expected to
 * handle.
 */
class PlannedRouteSaveTest {
    private val root: File = Files.createTempDirectory("grass-planned-route").toFile()

    @After
    fun deleteStore() {
        root.deleteRecursively()
    }

    @Test
    fun aSavedRouteIsAnRteWithOnePointPerVertexAndARecomputeSidecar() =
        runTest {
            val access = access()

            val saved = saved(access.savePlannedRoute(readyPlan(), NAME))

            assertEquals(TrackKind.Route, saved.kind)
            assertEquals(NAME, saved.name)
            assertEquals(GEOMETRY, saved.storedGeometry())
            assertEquals(RouteProfile.Hike, saved.recompute?.profile)
            assertEquals(
                RouteOptions(hills = RoutePreference.Avoid, roughSurfaces = RoutePreference.DontMind),
                saved.recompute?.options,
            )

            val document = root.listFiles().orEmpty().single { file -> file.name.endsWith(".gpx") }
            val text = document.readText()
            assertTrue("the route is an rte", text.contains("<rte>"))
            assertEquals("one rtept per vertex", GEOMETRY.size, Regex("<rtept ").findAll(text).count())
            assertFalse("a plan is never written as a recording", text.contains("<trk"))

            val sidecar = root.listFiles().orEmpty().single { file -> file.name.endsWith(".route.json") }
            val sidecarText = sidecar.readText()
            assertTrue(sidecarText.contains("\"version\":1"))
            assertTrue(sidecarText.contains("\"profile\":\"hike\""))
            assertTrue(sidecarText.contains("\"hills\":\"avoid\""))
            assertTrue(sidecarText.contains("\"rough_surfaces\":\"dont_mind\""))
        }

    @Test
    fun aSavedRouteIsRequestedAgainFromTheEngineAfterAReload() =
        runTest {
            val saved = saved(access().savePlannedRoute(readyPlan(), NAME))
            val reopened = access()
            assertEquals(Outcome.Success(Unit), reopened.reload())

            val reloaded = stored(reopened, saved.id)
            assertEquals(TrackKind.Route, reloaded.kind)
            assertEquals(saved.recompute, reloaded.recompute)
            assertEquals(GEOMETRY, reloaded.storedGeometry())

            val engine =
                RecordingEngine(Outcome.Success(listOf(Route(geometry = ENGINE_GEOMETRY, distanceMeters = 1.0, durationSeconds = 2.0))))
            val recomputed = recomputed(reopened.recomputeRoute(saved.id, engine))

            assertEquals(RouteGeometrySource.Engine, recomputed.source)
            assertEquals(ENGINE_GEOMETRY, recomputed.geometry)
            assertEquals(GEOMETRY, engine.requests.single().waypoints)
            assertEquals(RouteProfile.Hike, engine.requests.single().profile)
        }

    @Test
    fun aMissingOrBrokenSidecarShowsTheStoredLineInsteadOfFailingTheOpen() =
        runTest {
            val access = access()
            val saved = saved(access.savePlannedRoute(readyPlan(), NAME))
            val sidecar = root.listFiles().orEmpty().single { file -> file.name.endsWith(".route.json") }
            val engine =
                RecordingEngine(Outcome.Success(listOf(Route(geometry = ENGINE_GEOMETRY, distanceMeters = 1.0, durationSeconds = 2.0))))

            assertTrue(sidecar.delete())
            val withoutSidecar = access()
            assertEquals(Outcome.Success(Unit), withoutSidecar.reload())
            val reloaded = stored(withoutSidecar, saved.id)
            assertNull("no recompute without a sidecar", reloaded.recompute)
            val storedOnly = recomputed(withoutSidecar.recomputeRoute(saved.id, engine))
            assertEquals(RouteGeometrySource.Stored, storedOnly.source)
            assertEquals(GEOMETRY, storedOnly.geometry)
            assertTrue("the engine is not asked without a sidecar", engine.requests.isEmpty())

            sidecar.writeText("{not json")
            val corrupted = access()
            assertEquals(Outcome.Success(Unit), corrupted.reload())
            assertNull("an unreadable sidecar is not a request", stored(corrupted, saved.id).recompute)
            assertEquals(RouteGeometrySource.Stored, recomputed(corrupted.recomputeRoute(saved.id, engine)).source)
        }

    @Test
    fun aBuildWithNoEngineShowsTheStoredLine() =
        runTest {
            val access = access()
            val saved = saved(access.savePlannedRoute(readyPlan(), NAME))

            val recomputed = recomputed(access.recomputeRoute(saved.id, engine = null))

            assertEquals(RouteGeometrySource.Stored, recomputed.source)
            assertEquals(GEOMETRY, recomputed.geometry)
        }

    @Test
    fun aRouteWithOnePointIsSavedAndComesBackWhole() =
        runTest {
            val access = access()
            val plan = readyPlan().copy(options = listOf(option(listOf(GeoPoint(49.84, 24.03)))))

            val saved = saved(access.savePlannedRoute(plan, "One stop"))
            val reopened = access()
            assertEquals(Outcome.Success(Unit), reopened.reload())

            val reloaded = stored(reopened, saved.id)
            assertEquals(listOf(GeoPoint(49.84, 24.03)), reloaded.storedGeometry())
            assertEquals(TrackKind.Route, reloaded.kind)
        }

    @Test
    fun aDocumentWithARouteAndATrackBecomesOneRecordOfEachKind() =
        runTest {
            val access = access()

            val report = import(access, "osmand-planned-route.gpx")

            assertEquals(listOf(TrackKind.Track, TrackKind.Route), report.tracks.map { record -> record.kind })
            assertEquals(
                setOf(TrackKind.Track, TrackKind.Route),
                access.summaries().map { summary -> summary.kind }.toSet(),
            )
        }

    @Test
    fun anImportedWaypointKeepsItsNameAndCoordinatesThroughAnExport() =
        runTest {
            val access = access()
            val report = import(access, "garmin-waypoints-route.gpx")
            assertEquals(
                listOf("Trailhead", "Spring"),
                report.waypoints.map { waypoint -> waypoint.name },
            )
            val track = report.tracks.single { record -> record.kind == TrackKind.Track }
            assertEquals(report.waypoints, track.waypoints)

            val file = exported(TrackExport(access, RecordingPlatformActions()).fileFor(track.id))
            val text = file.bytes.toString(Charsets.UTF_8)
            assertEquals("both waypoints are written back", 2, Regex("<wpt ").findAll(text).count())
            assertTrue(text.contains("<name>Trailhead</name>"))
            assertTrue(text.contains("lat=\"49.8605\" lon=\"24.0515\""))

            val reread = import(access, "roundtrip-export.gpx", file.bytes)
            assertEquals(report.waypoints, reread.waypoints)
        }

    @Test
    fun aSavedRouteExportsAsAnRteAndComesBackAsARoute() =
        runTest {
            val access = access()
            val saved = saved(access.savePlannedRoute(readyPlan(), NAME))

            val file = exported(TrackExport(access, RecordingPlatformActions()).fileFor(saved.id))
            val text = file.bytes.toString(Charsets.UTF_8)
            assertTrue(text.contains("<rte>"))
            assertFalse(text.contains("<trk"))

            val reread = import(access, "saved-route.gpx", file.bytes)
            val route = reread.tracks.single()
            assertEquals(TrackKind.Route, route.kind)
            assertEquals(GEOMETRY, route.storedGeometry())
            assertNull("an export carries no recompute sidecar", route.recompute)
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

    private fun readyPlan(): RoutePlanState =
        RoutePlanState(
            start = RoutePlanPoint(GEOMETRY.first(), "Start"),
            destination = RoutePlanPoint(GEOMETRY.last(), "End"),
            status = RoutePlanStatus.Ready,
            options = listOf(option(GEOMETRY)),
            vias = listOf(RoutePlanPoint(GEOMETRY[1], "Via")),
            profile = RouteProfile.Hike,
            overridePreferences = RouteOptions(hills = RoutePreference.Avoid, roughSurfaces = RoutePreference.DontMind),
        )

    private fun option(geometry: List<GeoPoint>): RoutePlanOption =
        RoutePlanOption(
            geometry = geometry,
            distanceMeters = 1_200.0,
            durationSeconds = 900.0,
            arrivalEpochMillis = 0L,
        )

    private suspend fun import(
        access: TrackAccess,
        fileName: String,
        bytes: ByteArray = bytes(fileName),
    ): ImportReport =
        when (val outcome = access.import(fileName = fileName, bytes = bytes, cancellation = CancellationHandle.None)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("$fileName did not import: ${outcome.error}")
        }

    private suspend fun stored(
        access: TrackAccess,
        id: TrackId,
    ): StoredTrack =
        when (val outcome = access.stored(id)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("stored route missing: ${outcome.error}")
        }

    private fun saved(outcome: Outcome<StoredTrack>): StoredTrack =
        when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("save failed: ${outcome.error}")
        }

    private fun recomputed(outcome: Outcome<RecomputedRoute>): RecomputedRoute =
        when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("recompute failed: ${outcome.error}")
        }

    private fun exported(outcome: Outcome<PlatformFile>): PlatformFile =
        when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("export failed: ${outcome.error}")
        }

    private fun bytes(resource: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("tracks/$resource")) {
            "missing test resource tracks/$resource"
        }.use { it.readBytes() }

    private class RecordingEngine(
        private val answer: Outcome<List<Route>>,
    ) : PlannedRouteEngine {
        val requests = mutableListOf<RouteRequest>()

        override suspend fun route(
            request: RouteRequest,
            cancellation: CancellationHandle,
        ): Outcome<List<Route>> {
            requests += request
            return answer
        }
    }

    private companion object {
        const val NAME = "Kyivka loop"
        val GEOMETRY =
            listOf(
                GeoPoint(49.8400, 24.0300),
                GeoPoint(49.8425, 24.0350),
                GeoPoint(49.8450, 24.0400),
            )
        val ENGINE_GEOMETRY = listOf(GeoPoint(49.8400, 24.0300), GeoPoint(49.8450, 24.0400))
    }
}
