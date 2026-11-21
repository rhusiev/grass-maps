package nl.r1a.grassmaps.regions

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmaps.plugin.api.contract.SwitchId
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

/**
 * Resuming an archive with an HTTP `Range` request (T-0505): a kill leaves the
 * bytes already received in `.incoming/partial~<region>~<version>/package.zip`
 * and the next attempt asks for the rest.
 */
class RegionResumeTest {
    private lateinit var root: Path
    private lateinit var paths: RegionPaths
    private val logs = mutableListOf<String>()
    private val archive = FixturePackages.zip(FixturePackages.files())
    private val ranges: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    private val bodyLengths: MutableList<Int> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        root = Files.createTempDirectory("grass-resume")
        paths = RegionPaths(root.toFile())
    }

    @After
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun aKilledArchiveResumesWithARangeRequestAndKeepsItsBytes() =
        runTest {
            val kept = seedPartial(archive.copyOf(HALF))
            rangeServer(Mode.HONOUR).use { server ->
                val outcome = download(server.baseUrl)

                assertTrue(outcome is Outcome.Success)
                assertEquals(listOf<String?>("bytes=${kept.size}-"), ranges)
                assertEquals(listOf(archive.size - kept.size), bodyLengths)
                assertInstalled()
                assertFalse(partialFile().exists())
            }
        }

    @Test
    fun progressTotalsAreTheArchiveSizeForAFreshAndAResumedDownload() =
        runTest {
            rangeServer(Mode.HONOUR).use { server ->
                val fresh = mutableListOf<DownloadProgress>()
                download(server.baseUrl, fresh::add)
                assertEquals(setOf<Long?>(archive.size.toLong()), fresh.map { it.bytesTotal }.toSet())
                assertEquals(archive.size.toLong(), fresh.last().bytesDone)
            }
            seedPartial(archive.copyOf(HALF))
            rangeServer(Mode.HONOUR).use { server ->
                val resumed = mutableListOf<DownloadProgress>()
                download(server.baseUrl, resumed::add)
                assertEquals(setOf<Long?>(archive.size.toLong()), resumed.map { it.bytesTotal }.toSet())
                assertTrue(resumed.first().bytesDone > HALF)
            }
        }

    @Test
    fun aServerThatAnswers200ToARangeRequestRestartsCleanly() =
        runTest {
            seedPartial(ByteArray(HALF) { 7 })
            rangeServer(Mode.IGNORE_RANGE).use { server ->
                val outcome = download(server.baseUrl)

                assertTrue(outcome is Outcome.Success)
                assertEquals(listOf<String?>("bytes=$HALF-"), ranges)
                assertEquals(listOf(archive.size), bodyLengths)
                assertInstalled()
            }
        }

    @Test
    fun aContentRangeThatDoesNotMatchRestartsFromByteZero() =
        runTest {
            seedPartial(archive.copyOf(HALF))
            rangeServer(Mode.WRONG_START).use { server ->
                val outcome = download(server.baseUrl)

                assertTrue(outcome is Outcome.Success)
                assertEquals(listOf<String?>("bytes=$HALF-", null), ranges)
                assertInstalled()
            }
        }

    @Test
    fun aPartialThatIsAlreadyTheWholeArchiveNeedsNoBody() =
        runTest {
            seedPartial(archive)
            rangeServer(Mode.HONOUR).use { server ->
                val outcome = download(server.baseUrl)

                assertTrue(outcome is Outcome.Success)
                assertEquals(listOf<String?>("bytes=${archive.size}-"), ranges)
                assertEquals(emptyList<Int>(), bodyLengths)
                assertInstalled()
            }
        }

    @Test
    fun aStalePartialLongerThanTheArchiveRestarts() =
        runTest {
            seedPartial(archive + ByteArray(HALF))
            rangeServer(Mode.HONOUR).use { server ->
                val outcome = download(server.baseUrl)

                assertTrue(outcome is Outcome.Success)
                assertEquals(listOf<String?>("bytes=${archive.size + HALF}-", null), ranges)
                assertInstalled()
            }
        }

    @Test
    fun resumedBytesAreNeverTrustedWithoutTheWholePackageCheck() =
        runTest {
            // The first half is not the archive's own bytes, but the server's 206
            // for the second half is well-formed.
            seedPartial(ByteArray(HALF) { 9 })
            rangeServer(Mode.HONOUR).use { server ->
                val outcome = download(server.baseUrl)

                assertEquals(Outcome.Failure(AppError.CorruptData), outcome)
                assertFalse(paths.versionDirectory(REGION, VERSION).exists())
                assertFalse(paths.partialDirectory(REGION, VERSION).exists())
            }
        }

    @Test
    fun aBrokenConnectionKeepsThePartialAndTheNextAttemptResumes() =
        runTest {
            rangeServer(Mode.CUT_FIRST_BODY).use { server ->
                val first = download(server.baseUrl)

                assertEquals(Outcome.Failure(AppError.Network), first)
                val kept = partialFile().length()
                assertTrue(kept in 1 until archive.size)

                val second = download(server.baseUrl)

                assertTrue(second is Outcome.Success)
                assertEquals(listOf<String?>(null, "bytes=$kept-"), ranges)
                assertInstalled()
            }
        }

    @Test
    fun aPartialOfAnotherVersionIsDiscarded() =
        runTest {
            val stale = paths.partialDirectory(REGION, "2026-10-01-0")
            assertTrue(stale.mkdirs())
            File(stale, "package.zip").writeBytes(ByteArray(HALF))
            rangeServer(Mode.HONOUR).use { server ->
                download(server.baseUrl)

                assertFalse(stale.exists())
            }
        }

    private suspend fun download(
        baseUrl: String,
        onProgress: (DownloadProgress) -> Unit = {},
    ): Outcome<InstalledRegion> =
        RegionDownloader(HttpDownloadTransport(), paths, logs::add).download(
            request =
                RegionRequest(
                    url = "$baseUrl/package.zip",
                    region = REGION,
                    version = VERSION,
                    token = null,
                ),
            policy =
                DownloadPolicy(
                    switch = SWITCH,
                    connectTimeoutMs = 2_000,
                    readTimeoutMs = 5_000,
                    allowCleartextHttp = true,
                ),
            access = permissive(),
            onProgress = onProgress,
        )

    private fun seedPartial(bytes: ByteArray): ByteArray {
        val directory = paths.partialDirectory(REGION, VERSION)
        assertTrue(directory.mkdirs())
        File(directory, "package.zip").writeBytes(bytes)
        return bytes
    }

    private fun partialFile(): File = File(paths.partialDirectory(REGION, VERSION), "package.zip")

    private fun assertInstalled() {
        assertEquals(VERSION, CurrentPointer.read(paths.regionDirectory(REGION)))
        assertFalse(paths.partialDirectory(REGION, VERSION).exists())
    }

    private fun permissive(): NetworkAccess =
        object : NetworkAccess {
            override suspend fun <T> request(
                switch: SwitchId,
                block: suspend () -> Outcome<T>,
            ): Outcome<T> = block()
        }

    private enum class Mode { HONOUR, IGNORE_RANGE, WRONG_START, CUT_FIRST_BODY }

    private fun rangeServer(mode: Mode): TestHttpServer =
        TestHttpServer { exchange ->
            val header = exchange.requestHeaders.getFirst("Range")
            ranges += header
            val start = header?.removePrefix("bytes=")?.removeSuffix("-")?.toLongOrNull() ?: 0L
            when {
                header == null -> {
                    reply(exchange, mode, 200, archive, null)
                }

                mode == Mode.IGNORE_RANGE -> {
                    reply(exchange, mode, 200, archive, null)
                }

                start >= archive.size && start == archive.size.toLong() -> {
                    exchange.responseHeaders.set("Content-Range", "bytes */${archive.size}")
                    exchange.sendResponseHeaders(416, -1)
                }

                start > archive.size -> {
                    exchange.responseHeaders.set("Content-Range", "bytes */${archive.size}")
                    exchange.sendResponseHeaders(416, -1)
                }

                mode == Mode.WRONG_START -> {
                    val wrong = start - 1
                    val body = archive.copyOfRange(wrong.toInt(), archive.size)
                    reply(exchange, mode, 206, body, "bytes $wrong-${archive.size - 1}/${archive.size}")
                }

                else -> {
                    val body = archive.copyOfRange(start.toInt(), archive.size)
                    reply(exchange, mode, 206, body, "bytes $start-${archive.size - 1}/${archive.size}")
                }
            }
        }

    private fun reply(
        exchange: com.sun.net.httpserver.HttpExchange,
        mode: Mode,
        status: Int,
        body: ByteArray,
        contentRange: String?,
    ) {
        contentRange?.let { exchange.responseHeaders.set("Content-Range", it) }
        val cut = mode == Mode.CUT_FIRST_BODY && ranges.size == 1
        bodyLengths += body.size
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { out ->
            try {
                out.write(body, 0, if (cut) body.size / 2 else body.size)
                out.flush()
            } catch (exception: IOException) {
                // The client closed the connection.
            }
        }
    }

    private companion object {
        val SWITCH: SwitchId = SwitchId("network.region_updates")
        const val REGION = "fixture-mini"
        val VERSION = FixturePackages.FIXTURE_VERSION
        const val HALF = 40_000
    }
}
