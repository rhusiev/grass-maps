package nl.r1a.grassmap.map

import kotlinx.coroutines.runBlocking
import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.service.NetworkGate
import org.maplibre.compose.resource.MapRequestInterceptor
import org.maplibre.compose.resource.MapResourceRequest

/**
 * The map engine's only path to an HTTP tile: every network URL it asks for is
 * checked against the active tile template and then against the privacy switch
 * (architecture.md section 26). A blocked request is rewritten to the engine's
 * `local://` scheme, which cannot open a socket, so the gate is not a second
 * filter beside the engine's own HTTP stack but the thing that admits the URL.
 *
 * [MapRequestInterceptor] is synchronous and runs on the engine's resource
 * thread, never Main. The gate is an in-memory switch and counter, so
 * [runBlocking] completes without touching I/O; it must stay that way.
 */
internal class GatedTileInterceptor(
    private val gate: NetworkGate,
    private val switch: SwitchId,
    private val allowsUrl: (String) -> Boolean,
    private val userAgent: String,
) : MapRequestInterceptor {
    override fun rewriteUrl(request: MapResourceRequest): String? =
        when (decide(request.url)) {
            TileRequestDecision.Allowed -> null
            TileRequestDecision.Blocked -> BLOCKED_URL
        }

    /**
     * Adds the product User-Agent the OSM tile policy requires. The header is
     * not sent for a URL outside the active source, and this path does not ask
     * the gate again: [rewriteUrl] already decided and counted the tile.
     */
    override fun headers(request: MapResourceRequest): Map<String, String> = userAgentFor(request.url)

    internal fun userAgentFor(url: String): Map<String, String> {
        if (!isNetworkUrl(url) || !allowsUrl(url)) {
            return emptyMap()
        }
        return mapOf(USER_AGENT_HEADER to userAgent)
    }

    internal fun decide(url: String): TileRequestDecision {
        if (!isNetworkUrl(url)) {
            return TileRequestDecision.Allowed
        }
        if (!allowsUrl(url)) {
            return TileRequestDecision.Blocked
        }
        val permitted =
            runBlocking {
                gate.request(switch) { Outcome.Success(Unit) }
            }
        return if (permitted is Outcome.Success) {
            TileRequestDecision.Allowed
        } else {
            TileRequestDecision.Blocked
        }
    }

    private fun isNetworkUrl(url: String): Boolean = url.startsWith("https://") || url.startsWith("http://")

    internal companion object {
        const val BLOCKED_URL = "local://network-gate-blocked"
        const val USER_AGENT_HEADER = "User-Agent"
    }
}

/** The gate's answer for one engine URL. */
internal enum class TileRequestDecision {
    Allowed,
    Blocked,
}
