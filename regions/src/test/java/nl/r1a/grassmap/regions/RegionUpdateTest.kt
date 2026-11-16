package nl.r1a.grassmap.regions

import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.DownloadProblemKind
import nl.r1a.grassmap.plugin.api.model.DownloadTransferState
import nl.r1a.grassmap.plugin.api.model.DownloadsData
import nl.r1a.grassmap.plugin.api.model.DownloadsDefaults
import nl.r1a.grassmap.plugin.api.testing.fixture.CatalogFixture
import nl.r1a.grassmap.plugin.api.testing.fixture.TestRegions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Monthly updates (T-0507) against a local server: a new catalogue version is
 * found, the update installs beside the old version and replaces it only after
 * every component verified, two failed checks keep the old version, and a
 * cancel never leaves a half-installed region.
 */
class RegionUpdateTest {
    private lateinit var root: Path
    private lateinit var paths: RegionPaths
    private lateinit var scope: CoroutineScope
    private val versions = mutableMapOf<String, String>()
    private val packageRequests = Collections.synchronizedList(mutableListOf<String>())
    private val tamperedResponses = AtomicInteger()

    @Volatile
    private var isSlow = false

    @Volatile
    private var catalog: ByteArray = ByteArray(0)

    @Before
    fun setUp() {
        root = Files.createTempDirectory("grass-update")
        paths = RegionPaths(root.toFile())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        setCatalog(emptyMap())
    }

    @After
    fun tearDown() {
        scope.cancel()
        root.toFile().deleteRecursively()
    }

    @Test
    fun aNewCatalogueVersionIsFoundForInstalledRegionsOnly() =
        withStore { store ->
            install(store, CatalogFixture.LVIV)
            install(store, CatalogFixture.VINNYTSIA)
            setCatalog(mapOf(CatalogFixture.LVIV to NEW_VERSION))

            assertTrue(store.refresh() is Outcome.Success)

            assertEquals(listOf(CatalogFixture.LVIV), store.data.value.updates.map { it.id })
        }

    @Test
    fun anUpdateReplacesTheOldVersionAndTheMountFollowsThePointer() =
        withStore { store ->
            install(store, CatalogFixture.LVIV)
            setCatalog(mapOf(CatalogFixture.LVIV to NEW_VERSION))
            store.refresh()

            assertEquals(Outcome.Success(Unit), store.updateAll())
            awaitIdle(store)

            assertEquals(NEW_VERSION, mountedVersion(CatalogFixture.LVIV))
            assertEquals(listOf(NEW_VERSION), versionDirectories(CatalogFixture.LVIV))
            assertEquals(NEW_VERSION, store.data.value.installed(CatalogFixture.LVIV)?.version)
            assertTrue(store.data.value.updates.isEmpty())
            assertNoWorkDirectory()
        }

    @Test
    fun updateAllQueuesEveryOutdatedRegionAndNothingElse() =
        withStore { store ->
            install(store, CatalogFixture.LVIV)
            install(store, CatalogFixture.VINNYTSIA)
            install(store, THIRD_REGION)
            setCatalog(mapOf(CatalogFixture.LVIV to NEW_VERSION, THIRD_REGION to NEW_VERSION))
            store.refresh()
            packageRequests.clear()

            assertEquals(Outcome.Success(Unit), store.updateAll())
            awaitIdle(store)

            assertEquals(
                setOf(CatalogFixture.LVIV, THIRD_REGION),
                packageRequests.map { it.split('/')[1] }.toSet(),
            )
            assertEquals(OLD_VERSION, mountedVersion(CatalogFixture.VINNYTSIA))
            assertEquals(NEW_VERSION, mountedVersion(CatalogFixture.LVIV))
            assertEquals(NEW_VERSION, mountedVersion(THIRD_REGION))
            assertFalse(paths.regionDirectory(UNINSTALLED_REGION).exists())
        }

    @Test
    fun aFailedVerifyTwiceKeepsTheOldVersionMountedAndSaysSo() =
        withStore { store ->
            install(store, CatalogFixture.LVIV)
            setCatalog(mapOf(CatalogFixture.LVIV to NEW_VERSION))
            store.refresh()
            packageRequests.clear()
            tamperedResponses.set(Int.MAX_VALUE)

            store.updateAll()
            awaitIdle(store)

            assertEquals(2, packageRequests.size)
            assertEquals(DownloadProblemKind.UPDATE_FAILED, store.data.value.problem(CatalogFixture.LVIV)?.kind)
            assertEquals(OLD_VERSION, mountedVersion(CatalogFixture.LVIV))
            assertEquals(listOf(OLD_VERSION), versionDirectories(CatalogFixture.LVIV))
            assertEquals(OLD_VERSION, store.data.value.installed(CatalogFixture.LVIV)?.version)
            assertNoWorkDirectory()
        }

    @Test
    fun aSingleFailedVerifyIsRetriedOnceAndTheUpdateInstalls() =
        withStore { store ->
            install(store, CatalogFixture.LVIV)
            setCatalog(mapOf(CatalogFixture.LVIV to NEW_VERSION))
            store.refresh()
            packageRequests.clear()
            tamperedResponses.set(1)

            store.updateAll()
            awaitIdle(store)

            assertEquals(2, packageRequests.size)
            assertNull(store.data.value.problem(CatalogFixture.LVIV))
            assertEquals(NEW_VERSION, mountedVersion(CatalogFixture.LVIV))
        }

    @Test
    fun aFirstInstallThatFailsTwiceIsCorruptDataNotAFailedUpdate() =
        withStore { store ->
            tamperedResponses.set(Int.MAX_VALUE)

            store.startDownload(CatalogFixture.LVIV)
            awaitIdle(store)

            assertEquals(DownloadProblemKind.CORRUPT_DATA, store.data.value.problem(CatalogFixture.LVIV)?.kind)
        }

    @Test
    fun cancelDuringAnUpdateLeavesTheOldVersionIntactAndNothingPartial() =
        withStore { store ->
            install(store, CatalogFixture.LVIV)
            setCatalog(mapOf(CatalogFixture.LVIV to NEW_VERSION))
            store.refresh()
            isSlow = true

            store.updateAll()
            withTimeout(TIMEOUT_MS) {
                store.data.first { data ->
                    val transfer = data.transfer(CatalogFixture.LVIV)
                    transfer != null && transfer.state == DownloadTransferState.DOWNLOADING && transfer.bytesDone > 0
                }
            }
            assertEquals(OLD_VERSION, mountedVersion(CatalogFixture.LVIV))

            assertEquals(Outcome.Success(Unit), store.cancel(CatalogFixture.LVIV))

            assertNull(store.data.value.transfer(CatalogFixture.LVIV))
            assertNull(store.data.value.problem(CatalogFixture.LVIV))
            assertEquals(OLD_VERSION, mountedVersion(CatalogFixture.LVIV))
            assertEquals(listOf(OLD_VERSION), versionDirectories(CatalogFixture.LVIV))
            assertNoWorkDirectory()
        }

    private fun withStore(block: suspend (RegionStore) -> Unit) =
        runBlocking {
            server().use { server ->
                val store = store(server.baseUrl)
                try {
                    assertTrue(store.refresh() is Outcome.Success)
                    block(store)
                } finally {
                    store.close()
                }
            }
        }

    private suspend fun install(
        store: RegionStore,
        region: String,
    ) {
        assertEquals(Outcome.Success(Unit), store.startDownload(region))
        awaitIdle(store)
        assertEquals(OLD_VERSION, mountedVersion(region))
    }

    private suspend fun awaitIdle(store: RegionStore) {
        withTimeout(TIMEOUT_MS) { store.data.first { it.transfers.isEmpty() } }
    }

    private fun mountedVersion(region: String): String? =
        RegionMountResolver(paths.root)
            .resolveAll()
            .installed
            .firstOrNull { it.region == region }
            ?.version

    private fun versionDirectories(region: String): List<String> =
        paths
            .regionDirectory(region)
            .listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            .orEmpty()

    private fun assertNoWorkDirectory() {
        val entries = paths.incomingDirectory().listFiles()?.map { it.name }.orEmpty()
        assertEquals(emptyList<String>(), entries)
    }

    /** The catalogue at [OLD_VERSION], except for the regions named in [newer]. */
    private fun setCatalog(newer: Map<String, String>) {
        versions.clear()
        versions.putAll(newer)
        catalog =
            CatalogFixture.withCatalog { document ->
                val regions =
                    (document.getValue("regions") as JsonArray).map { element ->
                        val entry = element.jsonObject
                        val id = entry.getValue("id").jsonPrimitive.content
                        JsonObject(entry + ("version" to JsonPrimitive(newer[id] ?: OLD_VERSION)))
                    }
                JsonObject(document + ("regions" to JsonArray(regions)))
            }
    }

    private fun server(): TestHttpServer =
        TestHttpServer { exchange ->
            val path = exchange.requestURI.path
            val match = PACKAGE_PATH.matchEntire(path)
            when {
                path == "/${DownloadsDefaults.CATALOG_FILE}" -> send(exchange, 200, catalog)
                match == null -> send(exchange, 404, ByteArray(0))
                else -> {
                    packageRequests += path
                    val body = packageBody(match.groupValues[1], match.groupValues[2])
                    if (isSlow) sendSlowly(exchange, body) else send(exchange, 200, body)
                }
            }
        }

    private fun packageBody(
        region: String,
        version: String,
    ): ByteArray =
        if (tamperedResponses.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
            tampered(region, version)
        } else {
            FixturePackages.at(region, version)
        }

    /** The package with one declared file one byte longer, so its size check fails. */
    private fun tampered(
        region: String,
        version: String,
    ): ByteArray {
        val alerts = FixturePackages.files().getValue(ALERTS_FILE)
        return FixturePackages.withManifest(
            transform = { manifest ->
                manifest
                    .replace("\"region\": \"${TestRegions.DEFAULT_REGION}\"", "\"region\": \"$region\"")
                    .replace(FixturePackages.FIXTURE_VERSION, version)
            },
            overrides = mapOf(ALERTS_FILE to alerts + EXTRA_BYTE),
        )
    }

    private fun sendSlowly(
        exchange: HttpExchange,
        body: ByteArray,
    ) {
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
            // The client cancelled and closed the connection.
        }
    }

    private suspend fun store(baseUrl: String): RegionStore {
        val store =
            RegionStore(
                paths = paths,
                policy = DownloadPolicy(SWITCH, connectTimeoutMs = 2_000, readTimeoutMs = 5_000, allowCleartextHttp = true),
                network =
                    object : NetworkAccess {
                        override suspend fun <T> request(
                            switch: SwitchId,
                            block: suspend () -> Outcome<T>,
                        ): Outcome<T> = block()
                    },
                scope = scope,
                transport = HttpDownloadTransport(),
            )
        store.setServer(baseUrl, null)
        return store
    }

    private companion object {
        val SWITCH: SwitchId = SwitchId("network.region_updates")
        val PACKAGE_PATH = Regex("""/([a-z0-9][a-z0-9._-]*)/([a-z0-9][a-z0-9._-]*)/package\.zip""")
        val EXTRA_BYTE = byteArrayOf(0x20)
        const val THIRD_REGION = "ua-07"
        const val UNINSTALLED_REGION = "ua-09"
        const val OLD_VERSION = "2026-10-01"
        const val NEW_VERSION = CatalogFixture.VERSION
        const val ALERTS_FILE = "way-alerts.geojson"
        const val TIMEOUT_MS = 60_000L
        const val SLOW_CHUNK_BYTES = 4096
        const val SLOW_CHUNK_DELAY_MS = 40L
    }
}
