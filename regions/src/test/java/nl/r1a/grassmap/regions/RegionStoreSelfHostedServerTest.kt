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
import nl.r1a.grassmap.plugin.api.model.DownloadProblemKind
import nl.r1a.grassmap.plugin.api.testing.fixture.CatalogFixture
import nl.r1a.grassmap.plugin.api.testing.fixture.TestRegions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

/**
 * The region store against the real self-hostable server (T-0510): the Python
 * service in `server/` started as a child process, serving a pipeline-shaped
 * output directory that `server/publish-packs.py` turned into the served
 * layout. Skipped when `python3` is not on the PATH.
 */
class RegionStoreSelfHostedServerTest {
    private lateinit var work: Path
    private lateinit var paths: RegionPaths
    private lateinit var scope: CoroutineScope
    private val processes = mutableListOf<Process>()
    private val logs = mutableListOf<String>()
    private var tokensFile: Path? = null
    private val serverDirectory = TestRegions.repositoryRoot().resolve("server")

    @Before
    fun setUp() {
        assumeTrue("python3 is required", runCommand(listOf("python3", "--version")).first == 0)
        work = Files.createTempDirectory("grass-selfhosted")
        paths = RegionPaths(work.resolve("store").toFile())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        if (::scope.isInitialized) {
            scope.cancel()
        }
        processes.forEach { it.destroy() }
        processes.forEach { it.waitFor() }
        if (::work.isInitialized) {
            RegionStores.delete(work)
        }
    }

    @Test
    fun aServerWithTokensRefusesWithoutOneAndServesWithOne() =
        runBlocking {
            val packs = publishLviv()
            val token = createToken()
            val url = startServer(packs, requireTokens = true)
            val store = store()
            try {
                assertEquals(Outcome.Failure(AppError.Server(401)), store.setServer(url, null))
                assertEquals(DownloadProblemKind.TOKEN_REQUIRED, store.data.value.catalogProblem?.kind)

                assertEquals(Outcome.Failure(AppError.Server(401)), store.setServer(url, "wrong-token-0123456789"))
                assertEquals(DownloadProblemKind.TOKEN_REJECTED, store.data.value.catalogProblem?.kind)

                assertEquals(Outcome.Success(Unit), store.setServer(url, token))
                installLviv(store)
            } finally {
                store.close()
            }
        }

    @Test
    fun aServerWithoutTokensServesWithNoTokenAndIgnoresAWrongOne() =
        runBlocking {
            val url = startServer(publishLviv(), requireTokens = false)
            val store = store()
            try {
                assertEquals(Outcome.Success(Unit), store.setServer(url, "wrong-token-0123456789"))
                installLviv(store)
            } finally {
                store.close()
            }
        }

    @Test
    fun theTokenlessDownloadAlsoWorksWithNoTokenAtAll() =
        runBlocking {
            val url = startServer(publishLviv(), requireTokens = false)
            val store = store()
            try {
                assertEquals(Outcome.Success(Unit), store.setServer(url, null))
                installLviv(store)
                assertEquals(false, store.data.value.tokenPresent)
            } finally {
                store.close()
            }
        }

    @Test
    fun aResumedDownloadGets206WithAMatchingContentRange() {
        val url = startServer(publishLviv(), requireTokens = false)
        val archive = URL("$url/${CatalogFixture.LVIV}/${CatalogFixture.VERSION}/package.zip")
        val connection = archive.openConnection() as HttpURLConnection
        connection.setRequestProperty("Range", "bytes=100-")
        try {
            val total = work.resolve("packs/${CatalogFixture.LVIV}/${CatalogFixture.VERSION}/package.zip").toFile().length()
            assertEquals(206, connection.responseCode)
            assertEquals("bytes 100-${total - 1}/$total", connection.getHeaderField("Content-Range"))
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun installLviv(store: RegionStore) {
        assertTrue(store.startDownload(CatalogFixture.LVIV) is Outcome.Success)
        val done = withTimeout(60_000) { store.data.first { it.installed.size == 1 } }
        assertEquals(listOf(CatalogFixture.LVIV), done.installed.map { it.region })
        assertTrue(done.problems.isEmpty())
    }

    /** Writes the pipeline-shaped output for Lviv, then publishes it into the served layout. */
    private fun publishLviv(): Path {
        val output = Files.createDirectories(work.resolve("pipeline-out"))
        Files.write(output.resolve("catalog.json"), CatalogFixture.bytes())
        val version = Files.createDirectories(output.resolve(CatalogFixture.LVIV).resolve(CatalogFixture.VERSION))
        ZipInputStream(FixturePackages.at(CatalogFixture.LVIV).inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                Files.write(version.resolve(entry.name), zip.readBytes())
            }
        }
        val packs = work.resolve("packs")
        val (status, text) =
            runCommand(listOf("python3", "-I", serverDirectory.resolve("publish-packs.py").toString(), output.toString(), "--dest", packs.toString()))
        assertEquals(text, 0, status)
        return packs
    }

    private fun createToken(): String {
        val file = work.resolve("tokens.json")
        val (status, text) =
            runCommand(
                listOf("python3", "-m", "gm_server.token_cli", "--file", file.toString(), "create", "--label", "test"),
                directory = serverDirectory,
            )
        assertEquals(text, 0, status)
        tokensFile = file
        return text.lines().first { it.startsWith(TOKEN_PREFIX) }.removePrefix(TOKEN_PREFIX).trim()
    }

        private fun startServer(
        packs: Path,
        requireTokens: Boolean,
    ): String {
        val port = ServerSocket(0).use { it.localPort }
        val builder = ProcessBuilder("python3", "-m", "gm_server").directory(serverDirectory.toFile())
        builder.redirectErrorStream(true)
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        builder.environment().apply {
            put("GM_PACKS_DIR", packs.toString())
            put("GM_STATE_DIR", work.resolve("state").toString())
            tokensFile?.let { put("GM_TOKENS_FILE", it.toString()) }
            put("GM_REQUIRE_TOKENS", if (requireTokens) "1" else "0")
            put("GM_HOST", "127.0.0.1")
            put("GM_PORT", port.toString())
        }
        processes += builder.start()
        val url = "http://127.0.0.1:$port"
        awaitHealthy(url)
        return url
    }

    private fun awaitHealthy(url: String) {
        val deadline = System.nanoTime() + START_TIMEOUT_NS
        while (System.nanoTime() < deadline) {
            try {
                val connection = URL("$url/healthz").openConnection() as HttpURLConnection
                try {
                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        return
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (_: java.io.IOException) {
                Thread.sleep(POLL_MS)
            }
        }
        error("the server did not start")
    }

    private fun runCommand(
        command: List<String>,
        directory: Path? = null,
    ): Pair<Int, String> =
        try {
            val builder = ProcessBuilder(command).redirectErrorStream(true)
            directory?.let { builder.directory(it.toFile()) }
            val process = builder.start()
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor() to text
        } catch (_: java.io.IOException) {
            -1 to ""
        }

    private fun store(): RegionStore =
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
            transport = HttpDownloadTransport(),
            log = logs::add,
        )

    private companion object {
        val SWITCH: SwitchId = SwitchId("network.region_updates")
        const val TOKEN_PREFIX = "token (shown once): "
        const val START_TIMEOUT_NS = 20_000_000_000L
        const val POLL_MS = 100L
    }
}
