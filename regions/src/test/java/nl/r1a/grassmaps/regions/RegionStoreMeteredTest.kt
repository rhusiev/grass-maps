package nl.r1a.grassmaps.regions

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nl.r1a.grassmaps.plugin.api.contract.SwitchId
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.DownloadTransferState
import nl.r1a.grassmaps.plugin.api.model.DownloadsData
import nl.r1a.grassmaps.plugin.api.model.DownloadsDefaults
import nl.r1a.grassmaps.plugin.api.model.MobileDataPolicy
import nl.r1a.grassmaps.plugin.api.testing.fixture.CatalogFixture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

/** The mobile-data policy of the region store (T-0505), against a loopback server. */
class RegionStoreMeteredTest {
    private lateinit var root: Path
    private lateinit var paths: RegionPaths
    private lateinit var scope: CoroutineScope
    private val network = FakeMetered()
    private val packageRanges: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        root = Files.createTempDirectory("grass-metered")
        paths = RegionPaths(root.toFile())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        root.toFile().deleteRecursively()
    }

    @Test
    fun aMeteredNetworkWithWifiOnlyMakesTheTransferWaitWithoutContactingTheServer() =
        runBlocking<Unit> {
            network.metered.value = true
            server().use { server ->
                val store = store(server.baseUrl)
                try {
                    store.startDownload(CatalogFixture.LVIV)

                    val waiting = awaitState(store, DownloadTransferState.WAITING_FOR_WIFI)

                    assertTrue(waiting.networkMetered)
                    assertEquals(0, packageRanges.size)
                    assertTrue(waiting.installed.isEmpty())
                } finally {
                    store.close()
                }
            }
        }

    @Test
    fun theUserChoiceToUseMobileDataLetsTheWaitingTransferRun() =
        runBlocking<Unit> {
            network.metered.value = true
            server().use { server ->
                val store = store(server.baseUrl)
                try {
                    store.startDownload(CatalogFixture.LVIV)
                    awaitState(store, DownloadTransferState.WAITING_FOR_WIFI)

                    assertEquals(Outcome.Success(Unit), store.useMobileDataNow())

                    val done = awaitInstalled(store)
                    assertEquals(MobileDataPolicy.WIFI_ONLY, done.mobileDataPolicy)
                    assertEquals(1, packageRanges.size)
                } finally {
                    store.close()
                }
            }
        }

    @Test
    fun anUnmeteredNetworkReleasesTheWaitingTransfer() =
        runBlocking<Unit> {
            network.metered.value = true
            server().use { server ->
                val store = store(server.baseUrl)
                try {
                    store.startDownload(CatalogFixture.LVIV)
                    awaitState(store, DownloadTransferState.WAITING_FOR_WIFI)

                    network.metered.value = false

                    awaitInstalled(store)
                } finally {
                    store.close()
                }
            }
        }

    @Test
    fun theAnyNetworkPolicyRunsOnAMeteredNetwork() =
        runBlocking<Unit> {
            network.metered.value = true
            server().use { server ->
                val store = store(server.baseUrl, MobileDataPolicy.ANY)
                try {
                    store.startDownload(CatalogFixture.LVIV)

                    awaitInstalled(store)
                } finally {
                    store.close()
                }
            }
        }

    @Test
    fun switchingThePolicyToAnyReleasesAWaitingTransferAndIsReported() =
        runBlocking<Unit> {
            network.metered.value = true
            val saved = mutableListOf<MobileDataPolicy>()
            server().use { server ->
                val store = store(server.baseUrl, onPolicyChanged = saved::add)
                try {
                    store.startDownload(CatalogFixture.LVIV)
                    awaitState(store, DownloadTransferState.WAITING_FOR_WIFI)

                    store.setMobileDataPolicy(MobileDataPolicy.ANY)

                    awaitInstalled(store)
                    assertEquals(listOf(MobileDataPolicy.ANY), saved)
                } finally {
                    store.close()
                }
            }
        }

    @Test
    fun cancellingAWaitingTransferRemovesItAndNothingIsDownloaded() =
        runBlocking<Unit> {
            network.metered.value = true
            server().use { server ->
                val store = store(server.baseUrl)
                try {
                    store.startDownload(CatalogFixture.LVIV)
                    awaitState(store, DownloadTransferState.WAITING_FOR_WIFI)

                    store.cancel(CatalogFixture.LVIV)

                    assertTrue(
                        store.data.value.transfers
                            .isEmpty(),
                    )
                    network.metered.value = false
                    delay(SETTLE_MS)
                    assertEquals(0, packageRanges.size)
                } finally {
                    store.close()
                }
            }
        }

    @Test
    fun aNetworkThatTurnsMeteredMidTransferPausesAndResumesWithARangeRequest() =
        runBlocking<Unit> {
            val body = fixturePackageBytes()
            server(slowBody = body).use { server ->
                val store = store(server.baseUrl)
                try {
                    store.startDownload(CatalogFixture.LVIV)
                    withTimeout(WAIT_MS) {
                        store.data.first { it.transfer(CatalogFixture.LVIV)?.bytesDone.let { done -> done != null && done > 0 } }
                    }

                    network.metered.value = true
                    awaitState(store, DownloadTransferState.WAITING_FOR_WIFI)
                    val kept = partialSize()
                    assertTrue("the partial archive is kept", kept > 0)

                    network.metered.value = false
                    awaitInstalled(store)

                    assertEquals(2, packageRanges.size)
                    assertTrue(packageRanges[1]!!.startsWith("bytes="))
                } finally {
                    store.close()
                }
            }
        }

    @Test
    fun theTransferTotalIsTheArchiveSizeTheServerSentNotTheCataloguesUnpackedSize() =
        runBlocking<Unit> {
            val body = fixturePackageBytes()
            server(slowBody = body).use { server ->
                val store = store(server.baseUrl)
                try {
                    store.startDownload(CatalogFixture.LVIV)
                    val running =
                        withTimeout(WAIT_MS) {
                            store.data.first { it.transfer(CatalogFixture.LVIV)?.bytesDone.let { done -> done != null && done > 0 } }
                        }

                    assertEquals(body.size.toLong(), running.transfer(CatalogFixture.LVIV)?.bytesTotal)
                } finally {
                    store.close()
                }
            }
        }

    @Test
    fun aCancelDuringThePauseWindowStopsTheTransferAndKeepsNothing() =
        runBlocking<Unit> {
            val body = fixturePackageBytes()
            val reached = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            server(slowBody = body).use { server ->
                val store =
                    store(server.baseUrl, beforePause = {
                        reached.complete(Unit)
                        proceed.await()
                    })
                try {
                    store.startDownload(CatalogFixture.LVIV)
                    withTimeout(WAIT_MS) {
                        store.data.first { it.transfer(CatalogFixture.LVIV)?.bytesDone.let { done -> done != null && done > 0 } }
                    }
                    network.metered.value = true
                    withTimeout(WAIT_MS) { reached.await() }
                    val cancelling = launch { store.cancel(CatalogFixture.LVIV) }
                    delay(SETTLE_MS)
                    proceed.complete(Unit)

                    withTimeout(WAIT_MS) { cancelling.join() }
                    network.metered.value = false
                    delay(SETTLE_MS)

                    assertTrue(
                        store.data.value.transfers
                            .isEmpty(),
                    )
                    assertTrue(
                        store.data.value.installed
                            .isEmpty(),
                    )
                    assertEquals(1, packageRanges.size)
                    assertEquals(0L, partialSize())
                } finally {
                    store.close()
                }
            }
        }

    @Test
    fun cancellingAPausedTransferDeletesItsPartialArchive() =
        runBlocking<Unit> {
            server(slowBody = fixturePackageBytes()).use { server ->
                val store = store(server.baseUrl)
                try {
                    store.startDownload(CatalogFixture.LVIV)
                    withTimeout(WAIT_MS) {
                        store.data.first { it.transfer(CatalogFixture.LVIV)?.bytesDone.let { done -> done != null && done > 0 } }
                    }
                    network.metered.value = true
                    awaitState(store, DownloadTransferState.WAITING_FOR_WIFI)
                    assertTrue(partialSize() > 0)

                    store.cancel(CatalogFixture.LVIV)

                    assertEquals(0, partialDirectories())
                } finally {
                    store.close()
                }
            }
        }

    @Test
    fun theMobileDataChoiceEndsWhenCancellingEmptiesTheQueue() =
        runBlocking<Unit> {
            network.metered.value = true
            server(slowBody = fixturePackageBytes()).use { server ->
                val store = store(server.baseUrl)
                try {
                    store.startDownload(CatalogFixture.LVIV)
                    awaitState(store, DownloadTransferState.WAITING_FOR_WIFI)
                    store.useMobileDataNow()
                    awaitState(store, DownloadTransferState.DOWNLOADING)
                    store.cancel(CatalogFixture.LVIV)

                    store.startDownload(CatalogFixture.LVIV)

                    awaitState(store, DownloadTransferState.WAITING_FOR_WIFI)
                } finally {
                    store.close()
                }
            }
        }

    @Test
    fun theMobileDataChoiceOnAnEmptyQueueDoesNothing() =
        runBlocking<Unit> {
            network.metered.value = true
            server().use { server ->
                val store = store(server.baseUrl)
                try {
                    store.useMobileDataNow()
                    store.startDownload(CatalogFixture.LVIV)

                    awaitState(store, DownloadTransferState.WAITING_FOR_WIFI)
                    delay(SETTLE_MS)
                    assertFalse(packageRanges.isNotEmpty())
                } finally {
                    store.close()
                }
            }
        }

    private fun partialDirectories(): Int =
        paths
            .incomingDirectory()
            .listFiles()
            .orEmpty()
            .count { it.name.startsWith(PARTIAL) }

    private fun partialSize(): Long =
        paths
            .incomingDirectory()
            .listFiles()
            .orEmpty()
            .filter { it.name.startsWith(PARTIAL) }
            .sumOf { directory -> directory.listFiles().orEmpty().sumOf { it.length() } }

    private suspend fun awaitState(
        store: RegionStore,
        state: DownloadTransferState,
    ): DownloadsData =
        withTimeout(WAIT_MS) {
            store.data.first { it.transfer(CatalogFixture.LVIV)?.state == state }
        }

    private suspend fun awaitInstalled(store: RegionStore): DownloadsData =
        withTimeout(WAIT_MS) {
            store.data.first { data -> data.installed.any { it.region == CatalogFixture.LVIV } && data.transfers.isEmpty() }
        }

    private fun fixturePackageBytes(): ByteArray = FixturePackages.at(CatalogFixture.LVIV, CatalogFixture.VERSION)

    private suspend fun store(
        baseUrl: String,
        policy: MobileDataPolicy = MobileDataPolicy.WIFI_ONLY,
        onPolicyChanged: (MobileDataPolicy) -> Unit = {},
        beforePause: suspend (String) -> Unit = {},
    ): RegionStore {
        val store =
            RegionStore(
                paths = paths,
                policy =
                    DownloadPolicy(
                        switch = SWITCH,
                        connectTimeoutMs = 2_000,
                        readTimeoutMs = 5_000,
                        allowCleartextHttp = true,
                    ),
                network =
                    object : NetworkAccess {
                        override suspend fun <T> request(
                            switch: SwitchId,
                            block: suspend () -> Outcome<T>,
                        ): Outcome<T> = block()
                    },
                scope = scope,
                metered = network,
                initialMobileDataPolicy = policy,
                onMobileDataPolicyChanged = onPolicyChanged,
                beforePause = beforePause,
            )
        store.setServer(baseUrl, null)
        return store
    }

    /** Serves the catalogue and the package; [slowBody] streams it slowly and honours `Range`. */
    private fun server(slowBody: ByteArray? = null): TestHttpServer =
        TestHttpServer { exchange ->
            val path = exchange.requestURI.path
            if (path == "/${DownloadsDefaults.CATALOG_FILE}") {
                send(exchange, 200, CatalogFixture.bytes())
                return@TestHttpServer
            }
            if (path.startsWith("/outlines/")) {
                send(exchange, 404, ByteArray(0))
                return@TestHttpServer
            }
            val range = exchange.requestHeaders.getFirst("Range")
            packageRanges += range
            val full = slowBody ?: FixturePackages.at(CatalogFixture.LVIV, CatalogFixture.VERSION)
            val start = range?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull() ?: 0
            val part = full.copyOfRange(start, full.size)
            if (range != null) {
                exchange.responseHeaders.set("Content-Range", "bytes $start-${full.size - 1}/${full.size}")
            }
            exchange.sendResponseHeaders(if (range != null) 206 else 200, part.size.toLong())
            try {
                exchange.responseBody.use { out ->
                    var offset = 0
                    while (offset < part.size) {
                        val length = minOf(CHUNK_BYTES, part.size - offset)
                        out.write(part, offset, length)
                        out.flush()
                        offset += length
                        if (slowBody != null) Thread.sleep(CHUNK_DELAY_MS)
                    }
                }
            } catch (exception: IOException) {
                // The client paused and closed the connection.
            }
        }

    private class FakeMetered : MeteredNetworkMonitor {
        val metered = MutableStateFlow(false)
        override val isMetered: StateFlow<Boolean> = metered
    }

    private companion object {
        val SWITCH: SwitchId = SwitchId("network.region_updates")
        const val PARTIAL = "partial"

        // The loopback server sleeps 30 ms per 4 KiB chunk. Under parallel CI load
        // :regions:testDebugUnitTest once ran 119.5 s and this test failed at its
        // 60 s bound, though the same tree passed locally and alone in 58 s.
        const val WAIT_MS = 180_000L
        const val SETTLE_MS = 500L
        const val CHUNK_BYTES = 4096
        const val CHUNK_DELAY_MS = 30L
    }
}
