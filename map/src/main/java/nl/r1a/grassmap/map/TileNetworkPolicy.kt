package nl.r1a.grassmap.map

import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.service.NetworkGate

/**
 * The policy the map engine's tile interceptor applies, passed to
 * [prepareMapEngine] without exposing a maplibre type to the caller.
 *
 * [allowsUrl] answers only whether a URL belongs to the active tile source;
 * [gate] and [switch] decide whether the network permits the request at all.
 * [userAgent] is the OSM tile policy's identifying header.
 */
class TileNetworkPolicy(
    val gate: NetworkGate,
    val switch: SwitchId,
    val allowsUrl: (String) -> Boolean,
    val userAgent: String,
)
