package nl.r1a.grassmaps.plugins.example

import nl.r1a.grassmaps.plugin.api.provider.Provider
import nl.r1a.grassmaps.plugin.api.provider.ProviderContract

/**
 * A provider that throws on demand. It exists to prove that the host isolates
 * provider exceptions and disables the provider after three failures.
 */
class ThrowingProvider : Provider {
    override val contract: ProviderContract =
        ProviderContract(
            name = "example.throwing",
            version = "1.0.0",
        )

    var calls: Int = 0
        private set

    fun fail(): Nothing {
        calls += 1
        throw IllegalStateException("example provider failure")
    }
}
