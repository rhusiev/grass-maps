package nl.r1a.grassmap.regions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.CatalogPartKind
import nl.r1a.grassmap.plugin.api.model.DownloadProblemKind
import nl.r1a.grassmap.plugin.api.model.DownloadTransferState
import nl.r1a.grassmap.plugin.api.model.DownloadsData
import nl.r1a.grassmap.plugin.api.model.DownloadsDefaults
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The region store against a loopback server serving the real catalogue (T-0504).
 *
 * The catalogue is the T-0501 build output, all 25 oblasts; the packages are the
 * committed fixture re-tagged per region and version, so a whole-country offer
 * is exercised without three gigabytes of archives. Every request the client
 * makes is recorded, which is how the tokenless and token cases are checked.
 */
class RegionStoreLocalServerTest {
    private lateinit var root: Path
    private lateinit var paths: RegionPaths
    private lateinit var scope: CoroutineScope
    private val logs = mutableListOf<String>()
    private val token = "test-token-0123456789abcdef"

    @Before
    fun setUp() {
        root = Files.createTempDirectory("grass-store")
        paths = RegionPaths(root.toFile())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        root.toFile().deleteRecursively()
    }

    @Test
    fun theRealCatalogueIsReadAndItsDataDateIsShown() =
        runBlocking {
            catalogServer(CatalogFixture.bytes()).use { server ->
                val store = store(server.baseUrl)
                try {
                    assertTrue(store.refresh() is Outcome.Success)

                    val data = store.data.value
                    assertEquals(CatalogFixture.REGION_COUNT, data.catalog?.regions?.size)
                    assertTrue(data.installed.isEmpty())
                    val lviv = data.catalog!!.regions.single { it.id == CatalogFixture.LVIV }
                    assertEquals("Lviv Oblast", lviv.nameEn)
                    assertEquals("2026-11-03", lviv.dataDate)
                    assertEquals(
                        CatalogPartKind.ELEVATION_30M,
                        CatalogPartKind.fromWire(CatalogPartKind.ELEVATION_30M_WIRE),
                    )
                } finally {
                    store.close()
                }
            }
        }

    @Test
    fun allTwentyFiveOblastsDownloadFromTheLocalServerWithNoCap() =
        runBlocking {
            catalogServer(CatalogFixture.bytes()).use { server ->
                val store = store(server.baseUrl)
                try {
                    assertTrue(store.refresh() is Outcome.Success)
                    val ids = store.data.value.catalog!!.regions.map { it.id }

                    // Every entry is queued in one pass: no client-side subset and
                    // no queue length the catalogue can exceed.
                    for (id in ids) {
                        assertEquals("queueing $id", Outcome.Success(Unit), store.startDownload(id))
                    }
                    val done =
                        withTimeout(120_000) {
                            store.data.first { it.installed.size == ids.size }
                        }

                    assertEquals(ids.size, done.installed.size)
                    assertEquals(emptyList<String>(), done.transfers.map { it.region })
                    for (pack in done.installed) {
                        assertEquals(CatalogFixture.VERSION, pack.version)
                        assertEquals("2026-11-03", pack.dataDate)
                        assertTrue(pack.parts.contains(CatalogPartKind.MAP))
                        assertTrue(pack.parts.contains(CatalogPartKind.CONTOURS))
                    }
                    assertNoWorkDirectory()
                    assertNoProblems(done)
                } finally {
                    store.close()
                }
            }
        }

    @Test
    fun aTokenlessServerDownloadsWithoutAToken() =
        runBlocking {
            catalogServer(CatalogFixture.bytes()).use { server ->
                val store = store(server.baseUrl)
                try {
                    assertTrue(store.refresh() is Outcome.Success)
                    assertTrue(store.startDownload(CatalogFixture.LVIV) is Outcome.Success)
                    val done = withTimeout(60_000) { store.data.first { it.installed.size == 1 } }

                    assertEquals(false, done.tokenPresent)
                    assertTrue(server.authorizations().isNotEmpty())
                    assertTrue(server.authorizations().all { it == null })
                    assertEquals(listOf(CatalogFixture.LVIV), done.installed.map { it.region })
                } finally {
                    store.close()
                }
            }
        }

    @Test
    fun aServerWithTokensAsksForOneAndSaysWhenItRefusesIt() =
        runBlocking {
            catalogServer(CatalogFixture.bytes(), requiredToken = token).use { server ->
                val store = store(server.baseUrl)
                try {
                    val missing = store.refresh()
                    assertEquals(Outcome.Failure(AppError.Server(401)), missing)
                    assertEquals(
                        DownloadProblemKind.TOKEN_REQUIRED,
                        store.data.value.catalogProblem?.kind,
                    )

                    val wrong = store.setServer(server.baseUrl, "wrong-token-0123456789")
                    assertEquals(Outcome.Failure(AppError.Server(401)), wrong)
                    assertEquals(
                        DownloadProblemKind.TOKEN_REJECTED,
                        store.data.value.catalogProblem?.kind,
                    )

                    val accepted = store.setServer(server.baseUrl, token)
                    assertEquals(Outcome.Success(Unit), accepted)
                    assertTrue(store.data.value.tokenPresent)
                    assertFalse(store.data.value.toString().contains(token))

                    assertTrue(store.startDownload(CatalogFixture.LVIV) is Outcome.Success)
                    val done = withTimeout(60_000) { store.data.first { it.installed.size == 1 } }
                    assertTrue(done.catalogProblem == null)
                    assertTrue(done.problems.isEmpty())
                    assertTrue(server.authorizations().any { it == "Bearer $token" })
                    assertFalse(logs.any { it.contains(token) })
                } finally {
                    store.close()
                }
            }
        }

    @Test
    fun cancelDuringTheArchiveLeavesNoWorkDirectoryAndNoMount() =
        runBlocking {
            slowPackageServer(CatalogFixture.bytes(), fixturePackage(CatalogFixture.LVIV)).use { server ->
                val store = store(server.baseUrl)
                try {
                    assertTrue(store.refresh() is Outcome.Success)
                    assertTrue(store.startDownload(CatalogFixture.LVIV) is Outcome.Success)
                    withTimeout(60_000) {
                        store.data.first { data ->
                            val transfer = data.transfer(CatalogFixture.LVIV)
                            transfer != null &&
                                transfer.state == DownloadTransferState.DOWNLOADING &&
                                transfer.bytesDone > 0
                        }
                    }

                    assertEquals(Outcome.Success(Unit), store.cancel(CatalogFixture.LVIV))

                    val cancelled = store.data.value
                    assertEquals(null, cancelled.transfer(CatalogFixture.LVIV))
                    assertTrue(cancelled.installed.isEmpty())
                    assertEquals(null, cancelled.problem(CatalogFixture.LVIV))
                    assertNoWorkDirectory()
                    assertFalse(paths.regionDirectory(CatalogFixture.LVIV).exists())
                } finally {
                    store.close()
                }
            }
        }

    @Test
    fun removingARegionDeletesItsMountAndItsRow() =
        runBlocking {
            catalogServer(CatalogFixture.bytes()).use { server ->
                val store = store(server.baseUrl)
                try {
                    assertTrue(store.refresh() is Outcome.Success)
                    assertTrue(store.startDownload(CatalogFixture.LVIV) is Outcome.Success)
                    withTimeout(60_000) { store.data.first { it.installed.size == 1 } }

                    assertEquals(Outcome.Success(Unit), store.remove(CatalogFixture.LVIV))

                    assertTrue(store.data.value.installed.isEmpty())
                    assertFalse(paths.regionDirectory(CatalogFixture.LVIV).exists())
                } finally {
                    store.close()
                }
            }
        }

    @Test
    fun aBlockedPrivacySwitchNeverReachesTheServer() =
        runBlocking {
            catalogServer(CatalogFixture.bytes()).use { server ->
                val refusing =
                    object : NetworkAccess {
                        override suspend fun <T> request(
                            switch: SwitchId,
                            block: suspend () -> Outcome<T>,
                        ): Outcome<T> = Outcome.Failure(AppError.SwitchOff(switch))
                    }
                val store =
                    RegionStore(
                        paths = paths,
                        policy = policy(),
                        network = refusing,
                        scope = scope,
                        transport = HttpDownloadTransport(),
                        log = logs::add,
                    )
                try {
                    assertEquals(Outcome.Failure(AppError.SwitchOff(SWITCH)), store.refresh())
                    assertEquals(
                        DownloadProblemKind.SWITCH_OFF,
                        store.data.value.catalogProblem?.kind,
                    )
                    assertEquals(0, server.requestCount)
                } finally {
                    store.close()
                }
            }
        }

    private suspend fun store(baseUrl: String): RegionStore {
        val store =
            RegionStore(
                paths = paths,
                policy = policy(),
                network = permissive(),
                scope = scope,
                transport = HttpDownloadTransport(),
                log = logs::add,
            )
        store.setServer(baseUrl, null)
        return store
    }

    private fun policy(): DownloadPolicy =
        DownloadPolicy(
            switch = SWITCH,
            connectTimeoutMs = 2_000,
            readTimeoutMs = 5_000,
            allowCleartextHttp = true,
        )

    private fun permissive(): NetworkAccess =
        object : NetworkAccess {
            override suspend fun <T> request(
                switch: SwitchId,
                block: suspend () -> Outcome<T>,
            ): Outcome<T> = block()
        }

    private fun catalogServer(
        catalog: ByteArray,
        requiredToken: String? = null,
    ): TestHttpServer =
        TestHttpServer { exchange ->
            val expected = requiredToken?.let { "${BearerToken.SCHEME} $it" }
            if (expected != null && exchange.requestHeaders.getFirst(TestHttpServer.AUTHORIZATION) != expected) {
                send(exchange, 401, ByteArray(0))
                return@TestHttpServer
            }
            val path = exchange.requestURI.path
            when {
                path == "/${DownloadsDefaults.CATALOG_FILE}" -> {
                    send(exchange, 200, catalog)
                }

                else -> {
                    val match = PACKAGE_PATH.matchEntire(path)
                    if (match == null) {
                        send(exchange, 404, ByteArray(0))
                    } else {
                        send(exchange, 200, fixturePackage(match.groupValues[1], match.groupValues[2]))
                    }
                }
            }
        }

    /** The catalogue at once, and the one package slowly enough to cancel mid-body. */
    private fun slowPackageServer(
        catalog: ByteArray,
        body: ByteArray,
    ): TestHttpServer =
        TestHttpServer { exchange ->
            if (exchange.requestURI.path == "/${DownloadsDefaults.CATALOG_FILE}") {
                send(exchange, 200, catalog)
                return@TestHttpServer
            }
            exchange.responseHeaders.set(TestHttpServer.CONTENT_TYPE, TestHttpServer.APPLICATION_ZIP)
            exchange.sendResponseHeaders(200, body.size.toLong())
            try {
                exchange.responseBody.use { out ->
                    var offset = 0
                    while (offset < body.size) {
                        val length = minOf(SLOW_CHUNK_BYTES, body.size - offset)
                        out.write(body, offset, length)
                        out.flush()
                        offset += length
                        Thread.sleep(SLOW_CHUNK_DELAY_MS)
                    }
                }
            } catch (exception: IOException) {
                // The client cancelled and closed the connection; the test asserts on
                // the client's state, not on the server's write.
            }
        }

    private fun fixturePackage(
        region: String,
        version: String = CatalogFixture.VERSION,
    ): ByteArray = FixturePackages.at(region, version)

    private fun assertNoWorkDirectory() {
        val incoming = paths.incomingDirectory()
        if (!incoming.exists()) {
            return
        }
        val entries = incoming.listFiles()?.map { it.name }.orEmpty()
        assertEquals(emptyList<String>(), entries)
    }

    private fun assertNoProblems(data: DownloadsData) {
        assertTrue(data.problems.isEmpty())
        assertEquals(null, data.catalogProblem)
    }

    private companion object {
        val SWITCH: SwitchId = SwitchId("network.region_updates")
        val PACKAGE_PATH = Regex("""/([a-z0-9][a-z0-9._-]*)/([a-z0-9][a-z0-9._-]*)/package\.zip""")
        const val SLOW_CHUNK_BYTES = 4096
        const val SLOW_CHUNK_DELAY_MS = 15L
    }
}
