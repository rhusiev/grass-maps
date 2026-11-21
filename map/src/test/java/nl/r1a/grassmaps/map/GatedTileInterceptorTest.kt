package nl.r1a.grassmaps.map

import nl.r1a.grassmaps.plugin.api.contract.SwitchId
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.service.NetworkGate
import nl.r1a.grassmaps.plugin.api.service.NetworkSwitches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine's tile URL decision: the source allowlist and the privacy gate. */
class GatedTileInterceptorTest {
    private val tile = "https://tile.openstreetmap.org/5/10/20.png"

    @Test
    fun aTileUrlThatBelongsToTheSourceIsCountedAndAllowed() {
        val gate = RecordingGate(enabled = true)
        val interceptor = interceptor(gate)

        assertEquals(TileRequestDecision.Allowed, interceptor.decide(tile))
        assertEquals(1, gate.calls)
        assertEquals(NetworkSwitches.onlineTiles, gate.switches.single())
    }

    @Test
    fun aSwitchThatIsOffBlocksTheTileUrl() {
        val gate = RecordingGate(enabled = false)
        val interceptor = interceptor(gate)

        assertEquals(TileRequestDecision.Blocked, interceptor.decide(tile))
        assertEquals(1, gate.calls)
    }

    @Test
    fun aUrlOutsideTheSourceIsBlockedWithoutAskingTheGate() {
        val gate = RecordingGate(enabled = true)
        val interceptor = interceptor(gate)

        assertEquals(
            TileRequestDecision.Blocked,
            interceptor.decide("https://example.org/5/10/20.png"),
        )
        assertEquals(0, gate.calls)
    }

    @Test
    fun aLocalSchemeIsNotANetworkRequest() {
        val gate = RecordingGate(enabled = false)
        val interceptor = interceptor(gate)

        assertEquals(TileRequestDecision.Allowed, interceptor.decide("pmtiles://file:///region.pmtiles"))
        assertEquals(TileRequestDecision.Allowed, interceptor.decide("local://region/0/0/0"))
        assertEquals(0, gate.calls)
    }

    @Test
    fun theUserAgentGoesOnlyToTheAllowedTileUrl() {
        val interceptor = interceptor(RecordingGate(enabled = true))

        assertEquals(
            RasterUserAgentTest.VALUE,
            interceptor.userAgentFor(tile)[GatedTileInterceptor.USER_AGENT_HEADER],
        )
        assertTrue(interceptor.userAgentFor("https://example.org/5/10/20.png").isEmpty())
        assertTrue(interceptor.userAgentFor("pmtiles://file:///region.pmtiles").isEmpty())
    }

    private fun interceptor(gate: NetworkGate): GatedTileInterceptor =
        GatedTileInterceptor(
            gate = gate,
            switch = NetworkSwitches.onlineTiles,
            allowsUrl = { url -> url.startsWith("https://tile.openstreetmap.org/") },
            userAgent = RasterUserAgentTest.VALUE,
        )

    private class RecordingGate(
        private val enabled: Boolean,
    ) : NetworkGate {
        var calls: Int = 0
            private set
        val switches = mutableListOf<SwitchId>()

        override suspend fun <T> request(
            switch: SwitchId,
            block: suspend () -> Outcome<T>,
        ): Outcome<T> {
            calls += 1
            switches += switch
            return if (enabled) block() else Outcome.Failure(AppError.SwitchOff(switch))
        }
    }
}

private object RasterUserAgentTest {
    const val VALUE = "GrassMaps (test)"
}
