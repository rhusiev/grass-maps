package nl.r1a.grassmaps.regions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import nl.r1a.grassmaps.plugin.api.contract.SwitchId
import nl.r1a.grassmaps.plugin.api.error.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class RegionStoreAutoUpdateTest {
    private lateinit var root: Path
    private lateinit var scope: CoroutineScope
    private val changes = mutableListOf<Boolean>()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("grass-auto-update-store")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        root.toFile().deleteRecursively()
    }

    @Test
    fun theChoiceStartsFromTheStoredValueAndEachChangeIsReportedOnce() =
        runBlocking {
            val store = store(initialAutoUpdate = false)
            assertFalse(store.data.value.autoUpdate)

            assertEquals(Outcome.Success(Unit), store.setAutoUpdate(true))

            assertTrue(store.data.value.autoUpdate)
            assertEquals(listOf(true), changes)
            store.close()
        }

    private fun store(initialAutoUpdate: Boolean): RegionStore =
        RegionStore(
            paths = RegionPaths(root.toFile()),
            policy = DownloadPolicy(SwitchId("network.region_updates")),
            network =
                object : NetworkAccess {
                    override suspend fun <T> request(
                        switch: SwitchId,
                        block: suspend () -> Outcome<T>,
                    ): Outcome<T> = block()
                },
            scope = scope,
            initialAutoUpdate = initialAutoUpdate,
            onAutoUpdateChanged = { changes += it },
        )
}
