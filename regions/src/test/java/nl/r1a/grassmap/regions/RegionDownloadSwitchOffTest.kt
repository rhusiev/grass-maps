package nl.r1a.grassmap.regions

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.core.services.PrivacyNetworkGate
import nl.r1a.grassmap.core.services.PrivacySwitchSettings
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.service.NetworkSwitches
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** A privacy switch turned off during a region download (T-0616). */
class RegionDownloadSwitchOffTest {
    private lateinit var root: Path
    private lateinit var paths: RegionPaths
    private val switches = PrivacySwitchSettings()
    private val access = GateNetworkAccess(PrivacyNetworkGate(switches))

    @Before
    fun setUp() {
        root = Files.createTempDirectory("grass-switch-off")
        paths = RegionPaths(root.toFile())
    }

    @After
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun switchingOffMidDownloadCancelsAtTheNextChunkAndLeavesNothingInstalled() =
        runTest {
            switches.setEnabled(SWITCH, true)
            val transport = ChunkedTransport(FixturePackages.zip(FixturePackages.files()), switchOffAfterChunk = 2)

            val outcome = download(transport)

            assertEquals(Outcome.Failure(AppError.SwitchOff(SWITCH)), outcome)
            assertEquals(2, transport.chunksWritten)
            assertFalse(paths.regionDirectory(REGION).exists())
            assertEquals(emptyList<String>(), workDirectories())
            assertEquals(1L, switches.state(SWITCH).requests)
        }

    @Test
    fun aDownloadWithTheSwitchOffReachesNeitherTheTransportNorTheCounter() =
        runTest {
            val transport = ChunkedTransport(FixturePackages.zip(FixturePackages.files()), switchOffAfterChunk = 2)

            val outcome = download(transport)

            assertEquals(Outcome.Failure(AppError.SwitchOff(SWITCH)), outcome)
            assertEquals(0, transport.chunksWritten)
            assertEquals(0L, switches.state(SWITCH).requests)
        }

    private suspend fun download(transport: DownloadTransport): Outcome<InstalledRegion> =
        RegionDownloader(transport, paths).download(
            request = RegionRequest("https://example.invalid/package.zip", REGION, VERSION, token = null),
            policy = DownloadPolicy(switch = SWITCH, connectTimeoutMs = 2_000, readTimeoutMs = 5_000),
            access = access,
        )

    private fun workDirectories(): List<String> =
        paths.incomingDirectory().listFiles().orEmpty()
            .map { it.name }
            .filterNot { it.startsWith(PARTIAL_PREFIX) }

    /** Serves [body] in chunks and turns [SWITCH] off after chunk number [switchOffAfterChunk]. */
    private inner class ChunkedTransport(
        private val body: ByteArray,
        private val switchOffAfterChunk: Int,
    ) : DownloadTransport {
        var chunksWritten = 0
            private set

        override suspend fun get(
            request: DownloadRequest,
            sink: BodySink,
        ): Outcome<TransportResult> {
            sink.begin(offset = 0, totalBytes = body.size.toLong())
            body.asList().chunked(CHUNK_BYTES).forEach { chunk ->
                currentCoroutineContext().ensureActive()
                sink.write(chunk.toByteArray(), chunk.size)
                chunksWritten++
                if (chunksWritten == switchOffAfterChunk) {
                    switches.setEnabled(SWITCH, false)
                }
            }
            return Outcome.Success(TransportResult(status = 200))
        }
    }

    private companion object {
        const val REGION = "fixture-mini"
        const val VERSION = FixturePackages.FIXTURE_VERSION
        const val CHUNK_BYTES = 4_096
        const val PARTIAL_PREFIX = RegionPaths.PARTIAL_PREFIX
        val SWITCH = NetworkSwitches.regionUpdates
    }
}
