package nl.r1a.grassmap.app

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.r1a.grassmap.core.CommandDispatchers
import nl.r1a.grassmap.core.services.NetworkSwitches
import nl.r1a.grassmap.core.services.PrivacyNetworkGate
import nl.r1a.grassmap.core.services.PrivacySwitchSettings
import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.regions.BodySink
import nl.r1a.grassmap.regions.DownloadPolicy
import nl.r1a.grassmap.regions.DownloadRequest
import nl.r1a.grassmap.regions.DownloadTransport
import nl.r1a.grassmap.regions.GateNetworkAccess
import nl.r1a.grassmap.regions.NetworkAccess
import nl.r1a.grassmap.regions.RegionDownloader
import nl.r1a.grassmap.regions.RegionPaths
import nl.r1a.grassmap.regions.TransportResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    fun bypassWithoutADebuggableBuildIsRefusedBeforeTheNetwork() =
        runTest {
            val transport = RecordingTransport()
            val outcome = command(transport).execute(params(token = TOKEN, bypass = true))

            assertEquals(Outcome.Failure(AppError.PermissionDenied), outcome)
            assertEquals(0, transport.calls)
            assertTrue(logs.any { it.contains("bypass refused") })
            assertFalse(logs.any { it.contains(TOKEN) })
        }

    @Test
    fun theBypassWhenDebuggableIsLoggedAndReachesTheServer() =
        runTest {
            val transport = RecordingTransport(status = 401)
            val outcome = command(transport, debuggable = true).execute(params(token = TOKEN, bypass = true))

            assertEquals(Outcome.Failure(AppError.Server(401)), outcome)
            assertEquals(1, transport.calls)
            assertTrue(logs.any { it.contains("network policy=debug-bypass") })
            assertTrue(logs.any { it.contains("cleartext=permitted(debug build)") })
            assertEquals(true, transport.lastPolicy?.allowCleartextHttp)
            assertFalse(logs.any { it.contains(TOKEN) })
        }

    @Test
    fun aDebuggableBypassInstallsThroughTheFakeTransport() =
        runTest {
            val transport = PackageTransport()
            val outcome = command(transport, debuggable = true).execute(params(token = TOKEN, bypass = true))

            assertTrue(outcome is Outcome.Success)
            assertEquals(1, transport.calls)
            assertEquals(true, transport.lastPolicy?.allowCleartextHttp)
            assertEquals(FIXTURE_VERSION, File(root, "fixture-mini/current").readText().trim())
        }

    @Test
    fun aNonDebuggableBuildNeverAllowsCleartext() =
        runTest {
            val transport = RecordingTransport(status = 401)
            val command =
                RegionDownloadTestCommand(
                    downloader = { RegionDownloader(transport, RegionPaths(root), logs::add) },
                    network = PermissiveNetworkAccess,
                    debuggable = { false },
                    log = logs::add,
                )

            val outcome = command.execute(params(token = TOKEN, bypass = false))

            assertEquals(Outcome.Failure(AppError.Server(401)), outcome)
            assertEquals(1, transport.calls)
            assertEquals(false, transport.lastPolicy?.allowCleartextHttp)
            assertTrue(logs.any { it.contains("cleartext=https-only") })
        }

    @Test
    fun anInvalidTokenIsRefusedWithoutContactingTheServer() =
        runTest {
            val transport = RecordingTransport()
            val outcome = command(transport, debuggable = true).execute(params(token = "short", bypass = true))

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

    private fun command(
        transport: DownloadTransport,
        debuggable: Boolean = false,
    ): RegionDownloadTestCommand =
        RegionDownloadTestCommand(
            downloader = { RegionDownloader(transport, RegionPaths(root), logs::add) },
            network = GateNetworkAccess(PrivacyNetworkGate(PrivacySwitchSettings())),
            debuggable = { debuggable },
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
                put("version", JsonPrimitive(FIXTURE_VERSION))
                token?.let { put("token", JsonPrimitive(it)) }
                put("bypass", JsonPrimitive(bypass))
            },
        )

    private class RecordingTransport(
        private val status: Int = 200,
    ) : DownloadTransport {
        var calls: Int = 0
            private set
        var lastPolicy: DownloadPolicy? = null
            private set

        override suspend fun get(
            request: DownloadRequest,
            sink: BodySink,
        ): Outcome<TransportResult> {
            calls++
            lastPolicy = request.policy
            return Outcome.Success(TransportResult(status))
        }
    }

    /** Serves the committed T-0015 package as one zip body. */
    private class PackageTransport : DownloadTransport {
        var calls: Int = 0
            private set
        var lastPolicy: DownloadPolicy? = null
            private set
        private val packageBytes: ByteArray by lazy { fixturePackage() }

        override suspend fun get(
            request: DownloadRequest,
            sink: BodySink,
        ): Outcome<TransportResult> {
            calls++
            lastPolicy = request.policy
            return when (val written = sink.write(packageBytes, packageBytes.size)) {
                is Outcome.Failure -> written
                is Outcome.Success -> Outcome.Success(TransportResult(200))
            }
        }
    }

    private object PermissiveNetworkAccess : NetworkAccess {
        override suspend fun <T> request(
            switch: SwitchId,
            block: suspend () -> Outcome<T>,
        ): Outcome<T> = block()
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
        const val FIXTURE_VERSION = "2026-11-03-1"
        const val FIXTURE_DIRECTORY = "regions/fixture-mini/$FIXTURE_VERSION/"

        fun fixturePackage(): ByteArray {
            val manifestBytes = resourceBytes("${FIXTURE_DIRECTORY}manifest.json")
            val manifest = Json.parseToJsonElement(String(manifestBytes, Charsets.UTF_8)).jsonObject
            val files =
                manifest
                    .getValue("components")
                    .jsonArray
                    .map {
                        it.jsonObject
                            .getValue("file")
                            .jsonPrimitive.content
                    }
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifestBytes)
                zip.closeEntry()
                files.forEach { name ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(resourceBytes(FIXTURE_DIRECTORY + name))
                    zip.closeEntry()
                }
            }
            return output.toByteArray()
        }

        fun resourceBytes(path: String): ByteArray =
            requireNotNull(RegionCommandsTest::class.java.classLoader?.getResourceAsStream(path)) {
                "missing test resource $path"
            }.use { it.readBytes() }
    }
}
