package nl.r1a.grassmap.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.model.PluginState
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamRegistryTest {
    @Test
    fun declaredStreamIsSubscribableAndActivatesItsOwner() = runTest {
        val stream = TestStream(MutableStateFlow(5))
        val manifest = testManifest(streams = listOf(testStreamDeclaration()))
        val host = testHost(
            scheduler = testScheduler,
            manifest = manifest,
            streams = listOf(testGeneratedStream()),
        ) {
            TestPlugin(streams = listOf(stream.descriptor))
        }
        val access = StreamRegistry(host)

        assertEquals(PluginState.DECLARED, host.statusOf(PluginId(TEST_PLUGIN_ID))?.state)
        assertEquals(5, access.subscribe<Int>(TestStream.STREAM_ID).first())
        assertEquals(PluginState.RUNNING, host.statusOf(PluginId(TEST_PLUGIN_ID))?.state)

        stream.state.value = 9
        assertEquals(9, access.subscribe<Int>(TestStream.STREAM_ID).first())
    }

    @Test
    fun unknownStreamEmitsNothing() = runTest {
        val manifest = testManifest()
        val host = testHost(testScheduler, manifest) { TestPlugin() }
        val access = StreamRegistry(host)

        val values = access.subscribe<Int>(StreamId("test.missing")).toList()

        assertEquals(emptyList<Int>(), values)
    }
}
