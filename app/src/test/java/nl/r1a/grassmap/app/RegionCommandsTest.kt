package nl.r1a.grassmap.app

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmap.core.CommandDispatchers
import nl.r1a.grassmap.core.services.NetworkSwitches
import nl.r1a.grassmap.core.services.PrivacyNetworkGate
import nl.r1a.grassmap.core.services.PrivacySwitchSettings
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.regions.DownloadRequest
import nl.r1a.grassmap.regions.DownloadTransport
import nl.r1a.grassmap.regions.GateNetworkAccess
import nl.r1a.grassmap.regions.RegionDownloader
import nl.r1a.grassmap.regions.RegionPaths
import nl.r1a.grassmap.regions.TransportResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RegionCommandsTest {
    private lateinit var root: File
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("grass-region-command").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun noParametersAreInvalidInput() =
        runTest {
            assertEquals(Outcome.Failure(AppError.InvalidInput), command(RecordingTransport()).execute(JsonNull))
        }

    @Test
    fun anUnsafeRegionIsInvalidInput() =
        runTest {
            val params = params(region = "../escape")

            assertEquals(Outcome.Failure(AppError.InvalidInput), command(RecordingTransport()).execute(params))
        }

    @Test
    fun aMissingRegionsRootIsNotReady() =
        runTest {
            val command =
                RegionDownloadTestCommand(
                    downloader = { null },
                    network = GateNetworkAccess(PrivacyNetworkGate(PrivacySwitchSettings())),
                    log = logs::add,
                )

            assertEquals(Outcome.Failure(AppError.NotReady), command.execute(params()))
        }

    @Test
    fun theProductionSwitchRefusesTheRequestBeforeTheNetwork() =
        runTest {
            val transport = RecordingTransport()
            val outcome = command(transport).execute(params())

            assertEquals(Outcome.Failure(AppError.SwitchOff(NetworkSwitches.regionUpdates)), outcome)
            assertEquals(0, transport.calls)
        }

    @Test
    fun theBypassIsLoggedAndReachesTheServer() =
        runTest {
            val transport = RecordingTransport(status = 401)
            val outcome = command(transport).execute(params(token = TOKEN, bypass = true))

            assertEquals(Outcome.Failure(AppError.Server(401)), outcome)
            assertEquals(1, transport.calls)
            assertTrue(logs.any { it.contains("network policy=debug-bypass") })
            assertTrue(logs.any { it.contains("cleartext=permitted") })
            assertFalse(logs.any { it.contains(TOKEN) })
        }

    @Test
    fun anInvalidTokenIsRefusedWithoutContactingTheServer() =
        runTest {
            val transport = RecordingTransport()
            val outcome = command(transport).execute(params(token = "short", bypass = true))

            assertEquals(Outcome.Failure(AppError.InvalidInput), outcome)
            assertEquals(0, transport.calls)
        }

    @Test
    fun theTriggerIsAllowlistedForTheDebugChannel() =
        runTest {
            val container =
                AppContainer(
                    strings = KeyOnlyStrings,
                    dispatchers =
                        CommandDispatchers(
                            main = StandardTestDispatcher(testScheduler),
                            default = StandardTestDispatcher(testScheduler),
                            io = StandardTestDispatcher(testScheduler),
                        ),
                    regionsRootProvider = { root },
                )

            val entry = container.controlCommands.singleOrNull { it.id == REGION_DOWNLOAD_COMMAND }
            assertTrue("core.region.download_test is not in the control catalog", entry != null)
            assertEquals(true, entry?.allowlistedForControl)
        }

    private fun command(transport: DownloadTransport): RegionDownloadTestCommand =
        RegionDownloadTestCommand(
            downloader = { RegionDownloader(transport, RegionPaths(root), logs::add) },
            network = GateNetworkAccess(PrivacyNetworkGate(PrivacySwitchSettings())),
            log = logs::add,
        )

    private fun params(
        region: String = "fixture-mini",
        token: String? = null,
        bypass: Boolean = false,
    ): JsonObject =
        JsonObject(
            buildMap {
                put("url", JsonPrimitive("http://127.0.0.1:1/package.zip"))
                put("region", JsonPrimitive(region))
                put("version", JsonPrimitive("2026-11-03-1"))
                token?.let { put("token", JsonPrimitive(it)) }
                put("bypass", JsonPrimitive(bypass))
            },
        )

    private class RecordingTransport(
        private val status: Int = 200,
    ) : DownloadTransport {
        var calls: Int = 0
            private set

        override suspend fun get(
            request: DownloadRequest,
            sink: nl.r1a.grassmap.regions.BodySink,
        ): Outcome<TransportResult> {
            calls++
            return Outcome.Success(TransportResult(status))
        }
    }

    private object KeyOnlyStrings : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String = key

        override fun contains(key: String): Boolean = false
    }

    private companion object {
        const val TOKEN = "test-token-0123456789abcdef"
    }
}
