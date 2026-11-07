package nl.r1a.grassmap.routing

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteModifier
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.provider.RoutingEngineContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
            val engine = ValhallaRoutingEngine({ transport }, log = { messages += it })
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
        assertEquals(setOf(RouteProfile.Walk, RouteProfile.Bike, RouteProfile.Hike, RouteProfile.Car), capabilities.profiles)
        assertEquals(2, capabilities.maxAlternatives)
        // RoutingEngine 1.0.0 has no matchTrack method and the adapter never
        // calls Valhalla's trace API, so this must stay false until T-0301.
        assertFalse(capabilities.hasMatchTrack)
    }

    private fun request(): RouteRequest =
        RouteRequest(
            waypoints = listOf(GeoPoint(49.8419, 24.0316), GeoPoint(49.8365, 24.0250)),
            profile = RouteProfile.Walk,
        )

    private fun shape(): String = Polyline6.encode(listOf(GeoPoint(49.8419, 24.0316), GeoPoint(49.8365, 24.0250)))

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
    ) : ValhallaTransport {
        val requests = mutableListOf<String>()

        override fun route(requestJson: String): String {
            requests += requestJson
            if (requests.size <= failuresBeforeSuccess) {
                throw failure
            }
            return success
        }
    }
}
