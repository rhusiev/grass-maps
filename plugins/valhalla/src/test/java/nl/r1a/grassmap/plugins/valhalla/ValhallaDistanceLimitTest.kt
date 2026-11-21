package nl.r1a.grassmaps.plugins.valhalla

import com.valhalla.valhalla.ErrorResponse
import com.valhalla.valhalla.ValhallaException
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmaps.plugin.api.contract.CancellationHandle
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.provider.RouteProfile
import nl.r1a.grassmaps.plugin.api.provider.RouteRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class ValhallaDistanceLimitTest {
    private val limits =
        mapOf(
            RouteProfile.Walk to PEDESTRIAN_LIMIT_METERS,
            RouteProfile.Hike to PEDESTRIAN_LIMIT_METERS,
            RouteProfile.Bike to BICYCLE_LIMIT_METERS,
        )

    @Test
    fun theCapabilitiesCarryOneLimitPerProfile() {
        val capabilities = ValhallaRoutingEngine({ FailingTransport(RuntimeException("unused")) }).capabilities()

        for ((profile, limit) in limits) {
            assertEquals(profile.name, limit, capabilities.maxDistanceFor(profile), 0.0)
        }
        assertEquals(BICYCLE_LIMIT_METERS, capabilities.maxDistanceMeters, 0.0)
    }

    @Test
    fun aRequestOneMetreUnderTheLimitReachesTheEngine() =
        runTest {
            for ((profile, limit) in limits) {
                val transport = FailingTransport(RuntimeException("reached"))
                val engine = ValhallaRoutingEngine({ transport })

                engine.route(requestOver(profile, limit - 1.0), CancellationHandle.None)

                assertEquals(profile.name, 1, transport.calls)
            }
        }

    @Test
    fun aRequestOneMetreOverTheLimitIsTooFarForItsProfile() =
        runTest {
            for ((profile, limit) in limits) {
                val transport = FailingTransport(RuntimeException("reached"))
                val engine = ValhallaRoutingEngine({ transport })

                val result = engine.route(requestOver(profile, limit + 1.0), CancellationHandle.None)

                assertEquals(profile.name, Outcome.Failure(AppError.TooFar(limit)), result)
                assertEquals(profile.name, 0, transport.calls)
            }
        }

    @Test
    fun aBikeRouteBeyondThePedestrianLimitIsStillPlanned() =
        runTest {
            val transport = FailingTransport(RuntimeException("reached"))
            val engine = ValhallaRoutingEngine({ transport })

            engine.route(requestOver(RouteProfile.Bike, PEDESTRIAN_LIMIT_METERS + 1.0), CancellationHandle.None)

            assertEquals(1, transport.calls)
        }

    @Test
    fun valhallasDistanceLimitErrorIsTooFarWithTheProfileLimit() =
        runTest {
            for ((profile, limit) in limits) {
                val engine = ValhallaRoutingEngine({ FailingTransport(distanceLimitError()) })

                val result = engine.route(requestOver(profile, SHORT_METERS), CancellationHandle.None)

                assertEquals(profile.name, Outcome.Failure(AppError.TooFar(limit)), result)
            }
        }

    @Test
    fun anotherValhallaErrorStaysInternal() =
        runTest {
            val error = ValhallaException.Internal(ErrorResponse(NO_ROUTE_CODE, "No path could be found"))
            val engine = ValhallaRoutingEngine({ FailingTransport(error) })

            val result = engine.route(requestOver(RouteProfile.Walk, SHORT_METERS), CancellationHandle.None)

            val failure = (result as? Outcome.Failure)?.error
            assertTrue(failure is AppError.Internal)
        }

    private fun distanceLimitError(): ValhallaException =
        ValhallaException.Internal(ErrorResponse(DISTANCE_LIMIT_CODE, "Path distance exceeds the max distance limit"))

    /** Two waypoints on one meridian, so the straight line is exactly [meters]. */
    private fun requestOver(
        profile: RouteProfile,
        meters: Double,
    ): RouteRequest {
        val degrees = meters / EARTH_RADIUS_METERS * DEGREES_PER_HALF_TURN / PI
        return RouteRequest(
            waypoints = listOf(GeoPoint(START_LATITUDE, START_LONGITUDE), GeoPoint(START_LATITUDE + degrees, START_LONGITUDE)),
            profile = profile,
        )
    }

    private class FailingTransport(
        private val error: Exception,
    ) : ValhallaTransport {
        var calls = 0

        override fun route(requestJson: String): String {
            calls += 1
            throw error
        }
    }

    private companion object {
        const val PEDESTRIAN_LIMIT_METERS = 250_000.0
        const val BICYCLE_LIMIT_METERS = 500_000.0
        const val SHORT_METERS = 1_000.0
        const val DISTANCE_LIMIT_CODE = 154
        const val NO_ROUTE_CODE = 442
        const val EARTH_RADIUS_METERS = 6_371_000.0
        const val DEGREES_PER_HALF_TURN = 180.0
        const val START_LATITUDE = 10.0
        const val START_LONGITUDE = 24.0
    }
}
