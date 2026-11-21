package nl.r1a.grassmaps.regions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nl.r1a.grassmaps.plugin.api.contract.SwitchId
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.DownloadsData
import nl.r1a.grassmaps.plugin.api.model.DownloadsDefaults
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.RegionOutline
import nl.r1a.grassmaps.plugin.api.testing.fixture.CatalogFixture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

/** The region borders of T-0506: the parser, the on-disk cache and the store's fetch. */
class RegionOutlinesTest {
    private lateinit var root: Path
    private lateinit var paths: RegionPaths
    private lateinit var scope: CoroutineScope
    private val outlineRequests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        root = Files.createTempDirectory("grass-outlines")
        paths = RegionPaths(root.toFile())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        root.toFile().deleteRecursively()
    }

    @Test
    fun aMultiPolygonKeepsOnlyTheOuterRingOfEachPolygon() {
        val body =
            multiPolygon(
                """[[[[24,49],[25,49],[25,50],[24,49]],[[24.2,49.2],[24.4,49.2],[24.4,49.4],[24.2,49.2]]],[[[30,50],[31,50],[31,51]]]]""",
            )

        val outline = (RegionOutlineParser.parse("ua-46", body.toByteArray()) as Outcome.Success).value

        assertEquals("ua-46", outline.region)
        assertEquals(2, outline.polygons.size)
        assertEquals(listOf(GeoPoint(49.0, 24.0), GeoPoint(49.0, 25.0), GeoPoint(50.0, 25.0), GeoPoint(49.0, 24.0)), outline.polygons[0])
    }

    @Test
    fun aPolygonAndAFeatureAreReadToo() {
        val polygon = """{"type":"Polygon","coordinates":[[[24,49],[25,49],[25,50]]]}"""
        val feature = """{"type":"Feature","properties":{},"geometry":$polygon}"""

        assertTrue(RegionOutlineParser.parse("a", polygon.toByteArray()) is Outcome.Success)
        assertTrue(RegionOutlineParser.parse("a", feature.toByteArray()) is Outcome.Success)
    }

    @Test
    fun hostileFilesAreCorruptData() {
        val corrupt =
            listOf(
                "not json",
                """{"type":"Point","coordinates":[24,49]}""",
                multiPolygon("[]"),
                multiPolygon("[[[[24,49],[25,49]]]]"),
                multiPolygon("[[[[24,49],[25,49],[25,95]]]]"),
                multiPolygon("[[[[24,49],[25,49],[181,50]]]]"),
                multiPolygon("""[[[[24,49],["x",49],[25,50]]]]"""),
                multiPolygon("[[[[24],[25,49],[25,50]]]]"),
            )

        corrupt.forEach { body ->
            assertEquals(body, Outcome.Failure(AppError.CorruptData), RegionOutlineParser.parse("a", body.toByteArray()))
        }
    }

    @Test
    fun theCacheReturnsWhatItWroteAndSkipsADamagedFile() {
        val cache = RegionOutlineCache(paths)
        assertTrue(cache.write("ua-46", square(24.0).toByteArray()))
        assertTrue(cache.write("ua-61", "damaged".toByteArray()))

        assertNotNull(cache.read("ua-46"))
        assertNull(cache.read("ua-61"))
        assertNull(cache.read("ua-99"))
        assertEquals(listOf("ua-46"), cache.readAll().map { it.region })
    }

    @Test
    fun theCacheRefusesARegionNameThatEscapesItsDirectory() {
        val cache = RegionOutlineCache(paths)

        assertFalse(cache.write("../escape", square(24.0).toByteArray()))
        assertNull(cache.read("../escape"))
    }

    @Test
    fun theCatalogueRefreshFetchesEveryBorderOnceAndKeepsThemOnDisk() =
        runBlocking<Unit> {
            catalogServer().use { server ->
                val store = store(server.baseUrl)
                try {
                    val data = awaitOutlines(store, CatalogFixture.REGION_COUNT)

                    assertEquals(CatalogFixture.REGION_COUNT, data.outlines.size)
                    assertEquals(CatalogFixture.REGION_COUNT, outlineRequests.size)
                    assertEquals(CatalogFixture.REGION_COUNT, RegionOutlineCache(paths).readAll().size)
                    assertTrue(data.outlines.first { it.region == CatalogFixture.LVIV }.contains(GeoPoint(24.5, 24.5)))
                } finally {
                    store.close()
                }
            }
        }

    @Test
    fun aRestartedStoreShowsTheCachedBordersWithoutTheNetwork() =
        runBlocking<Unit> {
            catalogServer().use { server ->
                val first = store(server.baseUrl)
                try {
                    awaitOutlines(first, CatalogFixture.REGION_COUNT)
                } finally {
                    first.close()
                }
            }
            outlineRequests.clear()
            val offline = store(null)
            try {
                val data = awaitOutlines(offline, CatalogFixture.REGION_COUNT)

                assertEquals(CatalogFixture.REGION_COUNT, data.outlines.size)
                assertTrue(outlineRequests.isEmpty())
            } finally {
                offline.close()
            }
        }

    @Test
    fun aServerThatRefusesBordersLeavesTheCatalogueIntactAfterOneAttempt() =
        runBlocking<Unit> {
            catalogServer(outlineStatus = SERVER_ERROR).use { server ->
                val store = store(server.baseUrl)
                try {
                    val data = withTimeout(WAIT_MS) { store.data.first { it.catalog != null } }
                    withTimeout(WAIT_MS) { while (outlineRequests.isEmpty()) kotlinx.coroutines.delay(POLL_MS) }

                    assertEquals(CatalogFixture.REGION_COUNT, data.catalog?.regions?.size)
                    assertTrue(
                        store.data.value.outlines
                            .isEmpty(),
                    )
                    assertEquals(1, outlineRequests.size)
                } finally {
                    store.close()
                }
            }
        }

    private suspend fun awaitOutlines(
        store: RegionStore,
        count: Int,
    ): DownloadsData = withTimeout(WAIT_MS) { store.data.first { it.outlines.size >= count } }

    private fun store(baseUrl: String?): RegionStore {
        val store =
            RegionStore(
                paths = paths,
                policy = DownloadPolicy(switch = SWITCH, connectTimeoutMs = 2_000, readTimeoutMs = 5_000, allowCleartextHttp = true),
                network =
                    object : NetworkAccess {
                        override suspend fun <T> request(
                            switch: SwitchId,
                            block: suspend () -> Outcome<T>,
                        ): Outcome<T> = block()
                    },
                scope = scope,
            )
        if (baseUrl != null) {
            runBlocking { store.setServer(baseUrl, null) }
        }
        return store
    }

    private fun catalogServer(outlineStatus: Int = OK): TestHttpServer =
        TestHttpServer { exchange ->
            val path = exchange.requestURI.path
            when {
                path == "/${DownloadsDefaults.CATALOG_FILE}" -> {
                    send(exchange, OK, CatalogFixture.bytes())
                }

                path.startsWith("/outlines/") -> {
                    outlineRequests += path
                    send(exchange, outlineStatus, if (outlineStatus == OK) square(24.0).toByteArray() else ByteArray(0))
                }

                else -> {
                    send(exchange, NOT_FOUND, ByteArray(0))
                }
            }
        }

    private fun multiPolygon(coordinates: String) = """{"type":"MultiPolygon","coordinates":$coordinates}"""

    private fun square(origin: Double): String =
        multiPolygon(
            "[[[[$origin,$origin],[${origin + SIDE},$origin],[${origin + SIDE},${origin + SIDE}],[$origin,${origin + SIDE}],[$origin,$origin]]]]",
        )

    private companion object {
        val SWITCH: SwitchId = SwitchId("network.region_updates")
        const val OK = 200
        const val NOT_FOUND = 404
        const val SERVER_ERROR = 500
        const val SIDE = 1.0
        const val WAIT_MS = 60_000L
        const val POLL_MS = 20L
    }
}
