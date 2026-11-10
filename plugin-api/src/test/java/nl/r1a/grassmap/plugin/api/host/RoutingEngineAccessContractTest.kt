package nl.r1a.grassmap.plugin.api.host

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.provider.RoutingCapabilities
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine
import nl.r1a.grassmap.plugin.api.provider.RoutingEngineContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The routing-engine access contract (T-0302): a host with no provider is a
 * typed failure, and a host that has one returns that exact engine.
 */
class RoutingEngineAccessContractTest {
    @Test
    fun theInertAccessIsNotFound() =
        runTest {
            assertEquals(Outcome.Failure(AppError.NotFound), NoRoutingEngines.engine())
        }

    @Test
    fun anAccessReturnsTheEngineItResolves() =
        runTest {
            val engine = FakeEngine()
            val access = RoutingEngineAccess { Outcome.Success(engine) }

            val resolved = access.engine()

            assertSame(engine, (resolved as Outcome.Success).value)
        }

    private class FakeEngine : RoutingEngine {
        override val contract = RoutingEngineContract.REFERENCE

        override fun capabilities() =
            RoutingCapabilities(
                profiles = emptySet(),
                modifierKinds = emptySet(),
                maxAlternatives = 0,
                maxDistanceMeters = 0.0,
            )

        override suspend fun route(
            request: RouteRequest,
            cancellation: CancellationHandle,
        ): Outcome<List<Route>> = Outcome.Success(emptyList())
    }
}
