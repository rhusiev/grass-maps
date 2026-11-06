package nl.r1a.grassmap.plugin.api.provider

/**
 * A capability contract reference. Every capability contract has its own semver.
 */
data class ProviderContract(
    val name: String,
    val version: String,
)

/**
 * Base type for providers of a capability contract. The contract interface
 * itself (RoutingEngine, PlaceProvider, ...) is added by the task that owns it.
 */
interface Provider {
    val contract: ProviderContract
}
