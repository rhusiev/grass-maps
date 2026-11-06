package nl.r1a.grassmap.core

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.manifest.ProviderDeclaration
import nl.r1a.grassmap.plugin.api.model.ProviderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ProviderRegistryTest {
    @Test
    fun throwingProviderIsDisabledAfterThreeFailures() = runTest {
        val provider = ThrowingTestProvider()
        val manifest = testManifest(
            providers = listOf(ProviderDeclaration(contract = "test.throwing", version = "1.0.0")),
        )
        val host = testHost(testScheduler, manifest) {
            TestPlugin(providers = listOf(provider))
        }
        val registry = ProviderRegistry(host)

        repeat(3) {
            val result = registry.invoke(PluginId(TEST_PLUGIN_ID), provider.contract) { current ->
                (current as ThrowingTestProvider).fail()
            }
            assertEquals(
                Outcome.Failure(AppError.PluginFailed(PluginId(TEST_PLUGIN_ID))),
                result,
            )
        }

        assertEquals(3, provider.calls)
        val status = registry.statusOf(PluginId(TEST_PLUGIN_ID), provider.contract)
        assertNotNull(status)
        assertEquals(ProviderState.DISABLED, status?.state)
        assertEquals(3, status?.failures)

        val fourth = registry.invoke(PluginId(TEST_PLUGIN_ID), provider.contract) { current ->
            (current as ThrowingTestProvider).fail()
        }

        assertEquals(
            Outcome.Failure(AppError.NotReady),
            fourth,
        )
        assertEquals(3, provider.calls)
    }
}
