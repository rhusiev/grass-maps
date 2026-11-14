package nl.r1a.grassmap.plugins.valhalla

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.ElevationAccess
import nl.r1a.grassmap.plugin.api.model.AttributeValue
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteAttributes
import nl.r1a.grassmap.plugin.api.provider.RouteModifier
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.provider.RouteSegment
import nl.r1a.grassmap.plugin.api.provider.RoutingEngineContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.roundToInt

class ValhallaRoutingEngineTest {
    @Test
    fun aValidRequestIsSentAndParsed() =
        runTest {
            val transport = RecordingTransport(response(shape()))
            val engine = ValhallaRoutingEngine({ transport })

            val result = engine.route(request(), CancellationHandle.None)

            val routes =
                (result as? Outcome.Success<List<Route>>)?.value
                    ?: throw AssertionError("expected routes")
            assertEquals(1, routes.size)
            assertEquals(1_009.0, routes.single().distanceMeters, 0.5)
            assertEquals(1, transport.requests.size)
            assertTrue(transport.requests.single().contains("\"pedestrian\""))
        }

    @Test
    fun routeCallsAreSerialisedOnTheSharedNativeEngine() =
        runTest {
            val transport = GatedTransport(response(shape()))
            val engine = ValhallaRoutingEngine({ transport })
            val first = async(start = CoroutineStart.UNDISPATCHED) { engine.route(request(), CancellationHandle.None) }
            assertTrue(transport.awaitEntered())
            val second = async(start = CoroutineStart.UNDISPATCHED) { engine.route(request(), CancellationHandle.None) }

            var waited = 0
            while (transport.entered.get() <= 1 && waited < 20) {
                Thread.sleep(10)
                waited += 1
            }
            assertEquals(1, transport.entered.get())

            transport.release()
            assertTrue(first.await() is Outcome.Success)
            assertTrue(second.await() is Outcome.Success)
        }

    @Test
    fun oneWaypointIsInvalidInputAndTheEngineIsNotCalled() =
        runTest {
            val transport = RecordingTransport(response(shape()))
            val engine = ValhallaRoutingEngine({ transport })

            val result =
                engine.route(
                    request().copy(waypoints = listOf(GeoPoint(49.84, 24.03))),
                    CancellationHandle.None,
                )

            assertEquals(Outcome.Failure(AppError.InvalidInput), result)
            assertTrue(transport.requests.isEmpty())
        }

    @Test
    fun anOffGraphCostLineRetriesWithExclusionPolygons() =
        runTest {
            val transport =
                RecordingTransport(
                    failuresBeforeSuccess = 1,
                    success = response(shape()),
                    failure = RuntimeException("ValhallaError(code=173, No edges near start/end)"),
                )
            val messages = mutableListOf<String>()
            val engine = ValhallaRoutingEngine({ transport }, log = RoutingLog { message -> messages += message })
            val modifier =
                RouteModifier(
                    geometry = listOf(GeoPoint(49.9410, 23.9410), GeoPoint(49.9420, 23.9425)),
                    multiplier = 1.2,
                )

            val result = engine.route(request().copy(modifiers = listOf(modifier)), CancellationHandle.None)

            assertTrue(result is Outcome.Success)
            assertEquals(2, transport.requests.size)
            assertTrue(transport.requests[0].contains("linear_cost_factors"))
            assertTrue(transport.requests[1].contains("exclude_polygons"))
            assertEquals(
                listOf(
                    "route failed: ValhallaError(code=173, No edges near start/end)",
                    "cost line has no edges near it; using exclusion polygons",
                ),
                messages,
            )
        }

    @Test
    fun anEdgeWalkFailureRetriesWithExclusionPolygons() =
        runTest {
            val transport =
                RecordingTransport(
                    success = response(shape()),
                    failuresBeforeSuccess = 1,
                    failure = RuntimeException("ValhallaError(code=233, Failed to edge walk line feature)"),
                )
            val engine = ValhallaRoutingEngine({ transport })
            val modifier =
                RouteModifier(
                    geometry = listOf(GeoPoint(49.9410, 23.9410), GeoPoint(49.9420, 23.9425)),
                    addedSeconds = 45.0,
                )

            val result = engine.route(request().copy(modifiers = listOf(modifier)), CancellationHandle.None)

            assertTrue(result is Outcome.Success)
            assertEquals(2, transport.requests.size)
            assertTrue(transport.requests[1].contains("exclude_polygons"))
        }

    @Test
    fun anotherEngineFailureIsAnInternalError() =
        runTest {
            val transport =
                RecordingTransport(
                    failuresBeforeSuccess = 10,
                    success = response(shape()),
                    failure = RuntimeException("boom"),
                )
            val engine = ValhallaRoutingEngine({ transport })

            val result = engine.route(request(), CancellationHandle.None)

            assertTrue(result is Outcome.Failure)
            assertTrue((result as Outcome.Failure).error is AppError.Internal)
        }

    @Test
    fun anEmptyPackDirectoryIsATypedAbsentFailure() =
        runTest {
            val pack = temporaryDirectory()
            val messages = mutableListOf<String>()
            val engine = ValhallaRoutingEngine(packs = { pack.absolutePath }, log = RoutingLog { messages += it })

            val error = packFailure(engine.route(request(), CancellationHandle.None))

            assertEquals(AppError.RoutingPackUnavailable.Reason.Absent, error.reason)
            assertTrue(error.detail.orEmpty().contains(pack.absolutePath))
            assertEquals(1, messages.size)
            assertTrue(messages.single().startsWith("valhalla pack:"))
        }

    @Test
    fun aPackWithoutAConfigIsIncomplete() =
        runTest {
            val pack = temporaryDirectory()
            writeFile(pack, "admins.sqlite")
            writeFile(pack, "timezones.sqlite")
            writeFile(pack, "tiles.tar")
            val engine = ValhallaRoutingEngine(packs = { pack.absolutePath })

            val error = packFailure(engine.route(request(), CancellationHandle.None))

            assertEquals(AppError.RoutingPackUnavailable.Reason.Incomplete, error.reason)
            assertTrue(error.detail.orEmpty().contains("valhalla.json"))
        }

    @Test
    fun aPackWithoutTilesIsIncomplete() =
        runTest {
            val pack = temporaryDirectory()
            writeFile(pack, "valhalla.json", CONFIG)
            writeFile(pack, "admins.sqlite")
            writeFile(pack, "timezones.sqlite")
            val engine = ValhallaRoutingEngine(packs = { pack.absolutePath })

            val error = packFailure(engine.route(request(), CancellationHandle.None))

            assertEquals(AppError.RoutingPackUnavailable.Reason.Incomplete, error.reason)
            assertTrue(error.detail.orEmpty().contains("tiles.tar"))
        }

    @Test
    fun anUnreadableConfigIsUnusable() =
        runTest {
            val pack = temporaryDirectory()
            writeFile(pack, "valhalla.json", "{ not json")
            writeFile(pack, "admins.sqlite")
            writeFile(pack, "timezones.sqlite")
            writeFile(pack, "tiles.tar")
            val engine = ValhallaRoutingEngine(packs = { pack.absolutePath })

            val error = packFailure(engine.route(request(), CancellationHandle.None))

            assertEquals(AppError.RoutingPackUnavailable.Reason.Unusable, error.reason)
        }

    @Test
    fun aPackProblemAlsoReachesAnnotate() =
        runTest {
            val engine = ValhallaRoutingEngine(packs = { temporaryDirectory().resolve("missing").absolutePath })

            val error = packFailure(engine.annotate(aRoute(), RouteProfile.Walk, CancellationHandle.None))

            assertEquals(AppError.RoutingPackUnavailable.Reason.Absent, error.reason)
        }

    @Test
    fun aCancelledRequestDoesNoWork() =
        runTest {
            val transport = RecordingTransport(response(shape()))
            val engine = ValhallaRoutingEngine({ transport })

            val result =
                engine.route(request(), CancellationHandle { true })

            assertEquals(Outcome.Failure(AppError.Cancelled), result)
            assertTrue(transport.requests.isEmpty())
        }

    @Test
    fun theEngineDeclaresTheV1Capabilities() {
        val engine = ValhallaRoutingEngine({ RecordingTransport(response(shape())) })

        val capabilities = engine.capabilities()

        assertEquals(RoutingEngineContract.REFERENCE, engine.contract)
        assertEquals(setOf(RouteProfile.Walk, RouteProfile.Bike, RouteProfile.Hike), capabilities.profiles)
        assertEquals(2, capabilities.maxAlternatives)
        // Access is declared by the vocabulary but has no Valhalla source.
        assertEquals(RouteAttributes.V1 - RouteAttributes.ACCESS, capabilities.attributeKeys)
        // Evidence: the pack's loki actions list `trace_route` and
        // valhalla-mobile 0.6.4 exposes Valhalla.traceRouteRaw(String).
        assertTrue(capabilities.hasMatchTrack)
    }

    @Test
    fun annotateSplitsTheRouteByEdgeAndFillsTheAttributesTheTraceSources() =
        runTest {
            val transport = RecordingTransport(response(denseShape()), trace = traceResponse())
            val engine =
                ValhallaRoutingEngine(
                    { transport },
                    elevation = elevationAlong(DENSE_SHAPE_LONGITUDE) { distance -> 100.0 + distance * 0.01 },
                )

            val routed =
                (engine.route(request(), CancellationHandle.None) as Outcome.Success).value.single()
            val annotated =
                (engine.annotate(routed, RouteProfile.Walk, CancellationHandle.None) as Outcome.Success).value

            assertEquals("the legs stay as routed", routed.segments, annotated.segments)
            assertEquals(3, annotated.runs.size)
            val surface = annotated.runs.map { (it.attributes[RouteAttributes.SURFACE] as AttributeValue.TextValue).value }
            assertEquals(listOf("paved", "gravel", "paved"), surface)
            val highway = annotated.runs.map { (it.attributes[RouteAttributes.HIGHWAY] as AttributeValue.TextValue).value }
            assertEquals(listOf("footway", "path", "steps"), highway)
            val sac = annotated.runs.map { (it.attributes[RouteAttributes.SAC_SCALE] as AttributeValue.TextValue).value }
            assertEquals(listOf("none", "mountain_hiking", "none"), sac)
            assertTrue(annotated.runs.none { it.attributes.containsKey(RouteAttributes.ACCESS) })
            assertEquals(1.0, gradeOf(annotated.runs.first()), 0.1)
            assertEquals(1, transport.traces.size)
            assertTrue(transport.traces.single().contains("map_snap"))
        }

    @Test
    fun aTenMetreEdgeOverATwoMetreStepDoesNotReadTwentyPercent() =
        runTest {
            val transport = RecordingTransport(response(shape()), trace = traceOf(0.500, 0.010, 0.490))
            val engine =
                ValhallaRoutingEngine(
                    { transport },
                    elevation = elevationAlong(LONGITUDE_BASE) { distance -> if (distance < 505.0) 0.0 else 2.0 },
                )

            val annotated =
                (engine.annotate(lineRoute(line(1_000.0)), RouteProfile.Walk, CancellationHandle.None) as Outcome.Success).value
            val step = annotated.runs.single { it.distanceMeters < 20.0 }

            assertEquals(4.0, gradeOf(step), 0.2)
        }

    @Test
    fun aGenuineTwoHundredMetreClimbAtTwentyPercentStillReadsTwenty() =
        runTest {
            val transport = RecordingTransport(response(shape()), trace = traceOf(0.400, 0.200, 0.400))
            val engine =
                ValhallaRoutingEngine(
                    { transport },
                    elevation =
                        elevationAlong(LONGITUDE_BASE) { distance ->
                            when {
                                distance < 400.0 -> 0.0
                                distance < 600.0 -> (distance - 400.0) * 0.2
                                else -> 40.0
                            }
                        },
                )

            val annotated =
                (engine.annotate(lineRoute(line(1_000.0)), RouteProfile.Walk, CancellationHandle.None) as Outcome.Success).value
            val climb = annotated.runs.single { it.distanceMeters in 150.0..250.0 }

            assertEquals(20.0, gradeOf(climb), 0.2)
        }

    @Test
    fun aFlatKilometreReadsZeroGrade() =
        runTest {
            val transport = RecordingTransport(response(shape()), trace = traceOf(1.0))
            val engine = ValhallaRoutingEngine({ transport }, elevation = elevationAlong(LONGITUDE_BASE) { 0.0 })

            val annotated =
                (engine.annotate(lineRoute(line(1_000.0)), RouteProfile.Walk, CancellationHandle.None) as Outcome.Success).value

            assertEquals(0.0, gradeOf(annotated.runs.single()), 1e-9)
        }

    @Test
    fun aFailedTraceLeavesTheRouteUnannotatedForTheCaller() =
        runTest {
            val transport = RecordingTransport(response(shape()), traceFailure = RuntimeException("no trace"))
            val engine = ValhallaRoutingEngine({ transport })

            val routed =
                (engine.route(request(), CancellationHandle.None) as Outcome.Success).value.single()
            val result = engine.annotate(routed, RouteProfile.Walk, CancellationHandle.None)

            assertTrue(result is Outcome.Failure)
            assertTrue(routed.runs.isEmpty())
            assertEquals(
                0,
                routed.segments
                    .single()
                    .attributes.size,
            )
        }

    private fun packFailure(result: Outcome<*>): AppError.RoutingPackUnavailable =
        ((result as? Outcome.Failure)?.error as? AppError.RoutingPackUnavailable)
            ?: throw AssertionError("expected a typed pack failure, got $result")

    private fun temporaryDirectory(): File = Files.createTempDirectory("valhalla-engine").toFile().also { it.deleteOnExit() }

    private fun writeFile(
        directory: File,
        name: String,
        content: String = "",
    ) {
        val file = File(directory, name)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun aRoute(): Route =
        Route(
            geometry = listOf(GeoPoint(49.8419, 24.0316), GeoPoint(49.8365, 24.0250)),
            distanceMeters = 1_009.0,
            durationSeconds = 716.0,
        )

    /** A straight eastward line of [lengthMeters] at the test latitude, one vertex every 10 m. */
    private fun line(lengthMeters: Double): List<GeoPoint> {
        val steps = (lengthMeters / VERTEX_SPACING_METERS).roundToInt()
        return (0..steps).map { index ->
            GeoPoint(TEST_LATITUDE, LONGITUDE_BASE + (lengthMeters * index / steps) / METRES_PER_DEGREE_LONGITUDE)
        }
    }

    private fun lineRoute(points: List<GeoPoint>): Route {
        val length = points.zipWithNext().sumOf { (first, second) -> ValhallaRequest.distanceMetres(first, second) }
        return Route(geometry = points, distanceMeters = length, durationSeconds = length / 1.4)
    }

    /** Height as a function of distance from [baseLongitude], along the test line. */
    private fun elevationAlong(
        baseLongitude: Double,
        height: (Double) -> Double,
    ): ElevationAccess =
        ElevationAccess { point ->
            val distance = (point.longitude - baseLongitude) * METRES_PER_DEGREE_LONGITUDE
            Outcome.Success(height(distance))
        }

    private fun gradeOf(segment: RouteSegment): Double = (segment.attributes[RouteAttributes.GRADE] as AttributeValue.NumberValue).value

    private fun traceOf(vararg lengthsKm: Double): String {
        val edges =
            lengthsKm.joinToString(",") { length ->
                """{"length":$length,"surface":"paved","use":"footway","road_class":"service_other"}"""
            }
        return """{"edges":[$edges]}"""
    }

    private fun traceResponse(): String =
        """
        {"edges":[
          {"length":0.500,"surface":"paved","use":"footway","road_class":"service_other"},
          {"length":0.400,"surface":"gravel","use":"path","road_class":"service_other","sac_scale":2},
          {"length":0.109,"surface":"paved","use":"steps","road_class":"service_other","access_restriction":["private"]}
        ]}
        """.trimIndent()

    private fun request(): RouteRequest =
        RouteRequest(
            waypoints = listOf(GeoPoint(49.8419, 24.0316), GeoPoint(49.8365, 24.0250)),
            profile = RouteProfile.Walk,
        )

    private fun shape(): String = Polyline6.encode(listOf(GeoPoint(49.8419, 24.0316), GeoPoint(49.8365, 24.0250)))

    /** About one kilometre of geometry at roughly 100 m per point, so edges cut. */
    private fun denseShape(): String = Polyline6.encode((0..10).map { GeoPoint(49.8419, 24.0316 + it * 0.0015) })

    private fun response(shape: String): String =
        """
        {"trip":{"summary":{"length":1.009,"time":716.27},"legs":[
          {"summary":{"length":1.009,"time":716.27},"shape":"$shape","maneuvers":[]}
        ]}}
        """.trimIndent()

    private class GatedTransport(
        private val success: String,
    ) : ValhallaTransport {
        private val enteredLatch = CountDownLatch(1)
        private val releaseLatch = CountDownLatch(1)
        val entered = AtomicInteger(0)

        override fun route(requestJson: String): String {
            entered.incrementAndGet()
            enteredLatch.countDown()
            releaseLatch.await(5, TimeUnit.SECONDS)
            return success
        }

        fun awaitEntered(): Boolean = enteredLatch.await(5, TimeUnit.SECONDS)

        fun release() {
            releaseLatch.countDown()
        }
    }

    private class RecordingTransport(
        private val success: String = "{}",
        private val failuresBeforeSuccess: Int = 0,
        private val failure: RuntimeException = RuntimeException("boom"),
        private val trace: String = "{}",
        private val traceFailure: RuntimeException? = null,
    ) : ValhallaTransport {
        val requests = mutableListOf<String>()
        val traces = mutableListOf<String>()

        override fun route(requestJson: String): String {
            requests += requestJson
            if (requests.size <= failuresBeforeSuccess) {
                throw failure
            }
            return success
        }

        override fun traceAttributes(requestJson: String): String {
            traces += requestJson
            traceFailure?.let { throw it }
            return trace
        }
    }

    private companion object {
        /** A minimal pack config; the engine never reaches the native layer here. */
        const val CONFIG = """{"mjolnir":{"tile_extract":"/data/valhalla/tiles.tar"}}"""

        const val TEST_LATITUDE = 49.84
        const val LONGITUDE_BASE = 24.03
        const val DENSE_SHAPE_LONGITUDE = 24.0316
        const val VERTEX_SPACING_METERS = 10.0

        // The same small-distance scale ValhallaRequest.distanceMetres uses, so a
        // vertex lands on an exact edge boundary.
        val METRES_PER_DEGREE_LONGITUDE = 6_371_000.0 * cos(Math.toRadians(TEST_LATITUDE)) * Math.PI / 180.0
    }
}
