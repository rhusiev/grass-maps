package nl.r1a.grassmap.regions

import com.sun.net.httpserver.HttpExchange
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
import nl.r1a.grassmap.plugin.api.testing.fixture.CatalogFixture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

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
                    val ids =
                        store.data.value.catalog!!
                            .regions
                            .map { it.id }

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
                        store.data.value.catalogProblem
                            ?.kind,
                    )

                    val wrong = store.setServer(server.baseUrl, "wrong-token-0123456789")
                    assertEquals(Outcome.Failure(AppError.Server(401)), wrong)
                    assertEquals(
                        DownloadProblemKind.TOKEN_REJECTED,
                        store.data.value.catalogProblem
                            ?.kind,
                    )

                    val accepted = store.setServer(server.baseUrl, token)
                    assertEquals(Outcome.Success(Unit), accepted)
                    assertTrue(store.data.value.tokenPresent)
                    assertFalse(
                        store.data.value
                            .toString()
                            .contains(token),
                    )

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
    fun aQueuedTransferCancelledBeforeItsTurnNeverDownloads() =
        runBlocking {
            slowPackageServer(CatalogFixture.bytes(), fixturePackage(CatalogFixture.LVIV)).use { server ->
                val store = store(server.baseUrl)
                try {
                    assertTrue(store.refresh() is Outcome.Success)
                    assertTrue(store.startDownload(CatalogFixture.LVIV) is Outcome.Success)
                    withTimeout(60_000) {
                        store.data.first { data ->
                            data.transfer(CatalogFixture.LVIV)?.state == DownloadTransferState.DOWNLOADING
                        }
                    }
                    // Vinnytsia waits behind Lviv, so this cancel lands in the
                    // queue branch - the branch a worker claim can race.
                    assertTrue(store.startDownload(CatalogFixture.VINNYTSIA) is Outcome.Success)
                    assertEquals(
                        DownloadTransferState.WAITING,
                        store.data.value
                            .transfer(CatalogFixture.VINNYTSIA)
                            ?.state,
                    )

                    assertEquals(Outcome.Success(Unit), store.cancel(CatalogFixture.VINNYTSIA))
                    assertNull(store.data.value.transfer(CatalogFixture.VINNYTSIA))
                    assertEquals(Outcome.Success(Unit), store.cancel(CatalogFixture.LVIV))

                    val settled = store.data.value
                    assertNull(settled.problem(CatalogFixture.VINNYTSIA))
                    assertTrue(settled.installed.isEmpty())
                    assertFalse(paths.regionDirectory(CatalogFixture.VINNYTSIA).exists())
                    assertNoWorkDirectory()
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

                    assertTrue(
                        store.data.value.installed
                            .isEmpty(),
                    )
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
                        store.data.value.catalogProblem
                            ?.kind,
                    )
                    assertEquals(0, server.requestCount)
                } finally {
                    store.close()
                }
            }
        }

    @Test
    fun theElevationAddonInstallsBesideItsRegionAndRemovesWithIt() =
        runBlocking {
            val addon = ADDON_ID
            addonServer(addonPackage(addon, ADDON_VERSION)).use { server ->
                val store = store(server.baseUrl)
                try {
                    assertTrue(store.refresh() is Outcome.Success)
                    assertEquals(
                        "the add-on is refused before its region is installed",
                        Outcome.Failure(AppError.InvalidInput),
                        store.startDownload(addon),
                    )
                    assertFalse(File(paths.root, addon).exists())

                    assertTrue(store.startDownload(CatalogFixture.LVIV) is Outcome.Success)
                    withTimeout(60_000) { store.data.first { it.installed.size == 1 } }
                    val baseBefore = fileDigests(paths.regionDirectory(CatalogFixture.LVIV))

                    assertTrue(store.startDownload(addon) is Outcome.Success)
                    val done =
                        withTimeout(60_000) {
                            store.data.first {
                                it.transfers.isEmpty() && it.problems.isEmpty() &&
                                    it.installed
                                        .singleOrNull()
                                        ?.parts
                                        ?.contains(CatalogPartKind.ELEVATION_30M) == true
                            }
                        }

                    assertEquals("the add-on is not a region pack", listOf(CatalogFixture.LVIV), done.installed.map { it.region })
                    assertEquals(ADDON_VERSION, CurrentPointer.read(File(paths.root, addon)))
                    assertEquals("the base region files are untouched", baseBefore, fileDigests(paths.regionDirectory(CatalogFixture.LVIV)))
                    assertNoWorkDirectory()
                    assertTrue(RegionMountResolver(paths.root).elevationAddon(CatalogFixture.LVIV) != null)

                    assertEquals(Outcome.Success(Unit), store.remove(CatalogFixture.LVIV))
                    assertFalse(File(paths.root, addon).exists())
                    assertFalse(paths.regionDirectory(CatalogFixture.LVIV).exists())
                } finally {
                    store.close()
                }
            }
        }

    @Test
    fun removingTheRegionWhileItsAddonDownloadsIsRefusedAndKeepsTheBase() =
        runBlocking {
            val addon = ADDON_ID
            addonServer(addonPackage(addon, ADDON_VERSION), slowAddon = true).use { server ->
                val store = store(server.baseUrl)
                try {
                    assertTrue(store.refresh() is Outcome.Success)
                    assertTrue(store.startDownload(CatalogFixture.LVIV) is Outcome.Success)
                    withTimeout(60_000) { store.data.first { it.installed.size == 1 } }
                    val baseBefore = fileDigests(paths.regionDirectory(CatalogFixture.LVIV))
                    assertTrue(store.startDownload(addon) is Outcome.Success)
                    withTimeout(60_000) {
                        store.data.first { it.transfer(addon)?.state == DownloadTransferState.DOWNLOADING }
                    }

                    assertEquals(Outcome.Failure(AppError.InvalidInput), store.remove(CatalogFixture.LVIV))

                    assertEquals(baseBefore, fileDigests(paths.regionDirectory(CatalogFixture.LVIV)))
                    assertEquals(
                        listOf(CatalogFixture.LVIV),
                        store.data.value.installed
                            .map { it.region },
                    )
                    assertEquals(Outcome.Success(Unit), store.cancel(addon))
                    assertNoWorkDirectory()
                    assertFalse(File(paths.root, addon).exists())
                    assertEquals(Outcome.Success(Unit), store.remove(CatalogFixture.LVIV))
                } finally {
                    store.close()
                }
            }
        }

    @Test
    fun anAddonOfAnUnknownKindIsNotOffered() =
        runBlocking {
            val catalog = CatalogFixture.withElevationAddon().decodeToString().replace("\"elevation-30m\"", "\"mystery-pack\"")
            catalogServer(catalog.toByteArray()).use { server ->
                val store = store(server.baseUrl)
                try {
                    assertTrue(store.refresh() is Outcome.Success)

                    assertEquals(
                        Outcome.Failure(AppError.NotFound),
                        store.startDownload("${CatalogFixture.LVIV}-mystery-pack"),
                    )
                } finally {
                    store.close()
                }
            }
        }

    /** A map of each file's relative name to its size and SHA-256, to see a directory is unchanged. */
    private fun fileDigests(directory: File): Map<String, String> =
        directory
            .walkTopDown()
            .filter { it.isFile }
            .associate { it.relativeTo(directory).path to "${it.length()}:${sha256Hex(it.readBytes())}" }

    /** The add-on catalogue and packages: the base region fast, the add-on at once or slowly. */
    private fun addonServer(
        addonBody: ByteArray,
        slowAddon: Boolean = false,
    ): TestHttpServer =
        TestHttpServer { exchange ->
            val path = exchange.requestURI.path
            val match = PACKAGE_PATH.matchEntire(path)
            when {
                path == "/${DownloadsDefaults.CATALOG_FILE}" -> send(exchange, 200, CatalogFixture.withElevationAddon())
                match == null -> send(exchange, 404, ByteArray(0))
                match.groupValues[1] != ADDON_ID -> send(exchange, 200, fixturePackage(match.groupValues[1], match.groupValues[2]))
                slowAddon -> sendSlowly(exchange, addonBody)
                else -> send(exchange, 200, addonBody)
            }
        }

    private fun sendSlowly(
        exchange: HttpExchange,
        body: ByteArray,
    ) {
        exchange.responseHeaders.set(TestHttpServer.CONTENT_TYPE, TestHttpServer.APPLICATION_ZIP)
        exchange.sendResponseHeaders(200, body.size.toLong())
        try {
            exchange.responseBody.use { out ->
                var offset = 0
                while (offset < body.size) {
                    val length = minOf(ADDON_CHUNK_BYTES, body.size - offset)
                    out.write(body, offset, length)
                    out.flush()
                    offset += length
                    Thread.sleep(ADDON_CHUNK_DELAY_MS)
                }
            }
        } catch (exception: IOException) {
            // The client cancelled and closed the connection.
        }
    }

    private fun addonPackage(
        addon: String,
        version: String,
    ): ByteArray {
        val elevation = FixturePackages.files().getValue("elevation.bin")
        val manifest =
            """
            {"region": "$addon", "version": "$version", "packageFormat": 1,
             "osmTimestamp": "2026-11-03T21:24:23Z",
             "pipeline": {"addon": {"id": "elevation-30m", "region": "${CatalogFixture.LVIV}"}},
             "components": [{"kind": "elevation", "file": "elevation.bin", "format": "elevation-grid",
               "formatVersion": 1, "version": "$version", "bytes": ${elevation.size},
               "sha256": "${sha256Hex(elevation)}", "standIn": false}]}
            """.trimIndent()
        return FixturePackages.zip(
            linkedMapOf(
                RegionIdentifiers.MANIFEST_FILE to manifest.toByteArray(),
                "elevation.bin" to elevation,
            ),
        )
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

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
            val refused = expected != null && exchange.requestHeaders.getFirst(TestHttpServer.AUTHORIZATION) != expected
            val path = exchange.requestURI.path
            val match = PACKAGE_PATH.matchEntire(path)
            when {
                refused -> send(exchange, 401, ByteArray(0))
                path == "/${DownloadsDefaults.CATALOG_FILE}" -> send(exchange, 200, catalog)
                match == null -> send(exchange, 404, ByteArray(0))
                else -> send(exchange, 200, fixturePackage(match.groupValues[1], match.groupValues[2]))
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
            } else {
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
                    // The client cancelled and closed the connection; the test asserts
                    // on the client's state, not on the server's write.
                }
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
        const val ADDON_VERSION = "2026-01"
        val ADDON_ID = "${CatalogFixture.LVIV}-elevation-30m"
        const val SLOW_CHUNK_BYTES = 4096
        const val ADDON_CHUNK_BYTES = 32
        const val ADDON_CHUNK_DELAY_MS = 40L
        const val SLOW_CHUNK_DELAY_MS = 40L
    }
}
