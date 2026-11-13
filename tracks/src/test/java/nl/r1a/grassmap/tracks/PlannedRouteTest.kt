package nl.r1a.grassmap.tracks

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.ImportedEntities
import nl.r1a.grassmap.plugin.api.model.RoutePlanOption
import nl.r1a.grassmap.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmap.plugin.api.model.TrackKind
import nl.r1a.grassmap.plugin.api.model.TrackPoint
import nl.r1a.grassmap.plugin.api.model.TrackRoute
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteOptions
import nl.r1a.grassmap.plugin.api.provider.RoutePreference
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-0411 in `tracks`: the plan-to-route translation, the save that writes a
 * `<rte>` and its recompute sidecar, and the recompute that falls back to the
 * stored line.
 *
 * The engine is a fake in every case here. A test never reaches a routing
 * engine, and a build with none is a normal state the recompute handles.
 */
class PlannedRouteTest {
    @Test
    fun aReadyPlanBecomesTheRouteASaveKeeps() {
        val route = readyPlan().toPlannedRoute("Kyivka loop")

        assertEquals("Kyivka loop", route?.name)
        assertEquals(GEOMETRY, route?.geometry)
        assertEquals(RouteProfile.Hike, route?.recompute?.profile)
        assertEquals(
            "the one-route override is saved, not the profile default",
            RouteOptions(hills = RoutePreference.Avoid, roughSurfaces = RoutePreference.DontMind),
            route?.recompute?.options,
        )
        assertEquals(listOf("Start", "Via", "End"), route?.recompute?.waypoints?.map { point -> point.label })
    }

    @Test
    fun aPlanWithoutARouteIsNotSaveable() {
        assertNull("Idle", RoutePlanState().toPlannedRoute("x"))
        assertNull("Ready but no option", readyPlan().copy(options = emptyList()).toPlannedRoute("x"))
        assertNull("no destination", readyPlan().copy(destination = null).toPlannedRoute("x"))
        assertNull("an empty line", readyPlan().copy(options = listOf(option(emptyList()))).toPlannedRoute("x"))
    }

    @Test
    fun aSavedRouteIsARouteRecordBuiltFromTheWriterDocument() =
        runTest {
            val store = InMemoryTrackStore()
            val written = mutableListOf<ImportedEntities>()
            val saver =
                PlannedRouteSaver(
                    store = store,
                    writer = { entities ->
                        written += entities
                        Outcome.Success("doc".toByteArray())
                    },
                )

            val saved = success(saver.save(requireNotNull(readyPlan().toPlannedRoute("Kyivka loop"))))

            val document = written.single()
            assertTrue("a save writes a route and no track", document.tracks.isEmpty())
            assertEquals(
                3,
                document.routes
                    .single()
                    .points.size,
            )
            assertEquals(TrackKind.Route, saved.kind)
            assertEquals("Kyivka loop", saved.name)
            assertEquals(GEOMETRY, saved.storedGeometry())
            assertEquals(1, store.all().size)
            assertEquals(TrackKind.Route, store.summaries().single().kind)
            assertEquals(readyPlan().overridePreferences, saved.recompute?.options)
        }

    @Test
    fun aSaveWithoutALineOrWithoutTheWriterIsTypedRefusal() =
        runTest {
            val saver =
                PlannedRouteSaver(
                    store = InMemoryTrackStore(),
                    writer = { Outcome.Failure(AppError.Unsupported) },
                )

            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                saver.save(PlannedRoute(name = null, geometry = emptyList(), recompute = recompute())),
            )
            assertEquals(
                Outcome.Failure(AppError.Unsupported),
                saver.save(PlannedRoute(name = "x", geometry = GEOMETRY, recompute = recompute())),
            )
        }

    @Test
    fun aStoredRouteIsRequestedAgainThroughTheEngine() =
        runTest {
            val engine =
                RecordingEngine(Outcome.Success(listOf(Route(geometry = ENGINE_GEOMETRY, distanceMeters = 1.0, durationSeconds = 2.0))))

            val recomputed = PlannedRouteRecomputer(engine).recompute(routeRecord())

            assertEquals(RouteGeometrySource.Engine, recomputed.source)
            assertEquals(ENGINE_GEOMETRY, recomputed.geometry)
            val request = engine.requests.single()
            assertEquals("the chain in visit order", GEOMETRY, request.waypoints)
            assertEquals(RouteProfile.Hike, request.profile)
            assertEquals(RouteOptions(hills = RoutePreference.Avoid, roughSurfaces = RoutePreference.DontMind), request.options)
        }

    @Test
    fun theStoredLineIsShownWhenThereIsNoEngineNoAnswerOrNoSidecar() =
        runTest {
            val record = routeRecord()

            assertEquals(
                "no engine in this build",
                RecomputedRoute(GEOMETRY, RouteGeometrySource.Stored),
                PlannedRouteRecomputer(null).recompute(record),
            )
            assertEquals(
                "the engine answered with no route",
                RouteGeometrySource.Stored,
                PlannedRouteRecomputer(RecordingEngine(Outcome.Success(emptyList()))).recompute(record).source,
            )
            assertEquals(
                "the engine could not be reached",
                RouteGeometrySource.Stored,
                PlannedRouteRecomputer(RecordingEngine(Outcome.Failure(AppError.NotFound))).recompute(record).source,
            )
            assertEquals(
                "an imported route has no recompute sidecar",
                RouteGeometrySource.Stored,
                PlannedRouteRecomputer(RecordingEngine(Outcome.Success(listOf(Route(ENGINE_GEOMETRY, 1.0, 2.0)))))
                    .recompute(record.copy(recompute = null))
                    .source,
            )
            assertEquals(
                "a cancelled request is not sent",
                RouteGeometrySource.Stored,
                PlannedRouteRecomputer(RecordingEngine(Outcome.Success(listOf(Route(ENGINE_GEOMETRY, 1.0, 2.0)))))
                    .recompute(record, CancellationHandle { true })
                    .source,
            )
        }

    private fun readyPlan(): RoutePlanState =
        RoutePlanState(
            start = RoutePlanPoint(GeoPoint(49.8400, 24.0300), "Start"),
            destination = RoutePlanPoint(GeoPoint(49.8450, 24.0400), "End"),
            status = RoutePlanStatus.Ready,
            options = listOf(option(GEOMETRY)),
            vias = listOf(RoutePlanPoint(GeoPoint(49.8425, 24.0350), "Via")),
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

    private fun routeRecord(): StoredTrack {
        val line = TrackRoute(name = "Kyivka loop", points = GEOMETRY.map { position -> TrackPoint(position) })
        val track = line.asTrack()
        return StoredTrack(
            id = TrackId("a".repeat(64)),
            name = line.name,
            track = track,
            analysis = TrackAnalyzer().analyze(track),
            kind = TrackKind.Route,
            recompute = recompute(),
        )
    }

    private fun recompute(): PlannedRouteRecompute =
        PlannedRouteRecompute(
            profile = RouteProfile.Hike,
            options = RouteOptions(hills = RoutePreference.Avoid, roughSurfaces = RoutePreference.DontMind),
            start = RoutePlanPoint(GeoPoint(49.8400, 24.0300), "Start"),
            destination = RoutePlanPoint(GeoPoint(49.8450, 24.0400), "End"),
            vias = listOf(RoutePlanPoint(GeoPoint(49.8425, 24.0350), "Via")),
        )

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

    private fun <T> success(outcome: Outcome<T>): T =
        when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("unexpected failure: ${outcome.error}")
        }

    private companion object {
        val GEOMETRY =
            listOf(
                GeoPoint(49.8400, 24.0300),
                GeoPoint(49.8425, 24.0350),
                GeoPoint(49.8450, 24.0400),
            )
        val ENGINE_GEOMETRY = listOf(GeoPoint(49.8400, 24.0300), GeoPoint(49.8450, 24.0400))
    }
}
