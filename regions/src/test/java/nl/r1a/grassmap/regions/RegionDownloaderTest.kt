package nl.r1a.grassmap.regions

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.testing.fixture.TestRegions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

class RegionDownloaderTest {
    private lateinit var root: Path
    private lateinit var paths: RegionPaths
    private val logs = mutableListOf<String>()
    private val token = "test-token-0123456789abcdef"

    @Before
    fun setUp() {
        root = Files.createTempDirectory("grass-regions")
        paths = RegionPaths(root.toFile())
    }

    @After
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun aRequestWithoutATokenGets401AndInstallsNothing() =
        runTest {
            authenticatedPackageServer(token, FixturePackages.zip(FixturePackages.files())).use { server ->
                val outcome = download(server.baseUrl, token = null)

                assertEquals(Outcome.Failure(AppError.Server(401)), outcome)
                assertEquals(listOf<String?>(null), server.authorizations())
                assertNoInstall()
            }
        }

    @Test
    fun aWrongTokenGets401AndTheCorrectTokenInstalls() =
        runTest {
            authenticatedPackageServer(token, FixturePackages.zip(FixturePackages.files())).use { server ->
                val wrong = download(server.baseUrl, "wrong-token-0123456789")
                assertEquals(Outcome.Failure(AppError.Server(401)), wrong)
                assertNoInstall()

                val installed = success(download(server.baseUrl, token = token))
                assertEquals(FixturePackages.FIXTURE_VERSION, installed.currentVersion)
                assertEquals(12, installed.manifest.components.size)
                assertEquals(116_203L, installed.verifiedBytes)
                assertEquals(
                    listOf("Bearer ${"wrong-token-0123456789"}", "Bearer $token"),
                    server.authorizations(),
                )
                assertInstalledOnDisk()
            }
        }

    @Test
    fun aReinstallReusesTheVerifiedVersion() =
        runTest {
            authenticatedPackageServer(token, FixturePackages.zip(FixturePackages.files())).use { server ->
                success(download(server.baseUrl))
                val second = success(download(server.baseUrl))

                assertTrue(second.reusedExisting)
                assertInstalledOnDisk()
            }
        }

    @Test
    fun aCorruptComponentIsRejectedAndTheOldInstallStaysCurrent() =
        runTest {
            authenticatedPackageServer(token, FixturePackages.zip(FixturePackages.files())).use { server ->
                success(download(server.baseUrl))
            }
            val oldBytes = Files.readAllBytes(paths.versionDirectory(REGION, VERSION).resolve("map.pmtiles").toPath())
            val corrupt = corruptVariant()
            authenticatedPackageServer(token, corrupt).use { server ->
                val outcome = download(server.baseUrl, version = NEXT_VERSION)

                assertEquals(Outcome.Failure(AppError.CorruptData), outcome)
            }

            assertEquals(VERSION, CurrentPointer.read(paths.regionDirectory(REGION)))
            assertTrue(Files.readAllBytes(paths.versionDirectory(REGION, VERSION).resolve("map.pmtiles").toPath()).contentEquals(oldBytes))
            assertFalse(paths.versionDirectory(REGION, NEXT_VERSION).exists())
        }

    @Test
    fun aCorruptCurrentVersionIsReplacedByAVerifiedCopy() =
        runTest {
            authenticatedPackageServer(token, FixturePackages.zip(FixturePackages.files())).use { server ->
                success(download(server.baseUrl))
                val currentMap = paths.versionDirectory(REGION, VERSION).resolve("map.pmtiles")
                val corrupt = Files.readAllBytes(currentMap.toPath())
                corrupt[0] = (corrupt[0].toInt() xor 0xff).toByte()
                Files.write(currentMap.toPath(), corrupt)

                val replaced = success(download(server.baseUrl))

                assertFalse(replaced.reusedExisting)
                assertEquals(VERSION, CurrentPointer.read(paths.regionDirectory(REGION)))
                val expected = FixturePackages.files().getValue("map.pmtiles")
                assertTrue(Files.readAllBytes(currentMap.toPath()).contentEquals(expected))
                assertFalse(Files.list(paths.incomingDirectory().toPath()).use { it.findAny().isPresent })
            }
        }

    @Test
    fun aCancelledVerifyLeavesTheCorruptCurrentInPlace() {
        val fixture = FixturePackages.loadedRegion()
        val versionDirectory = paths.versionDirectory(REGION, VERSION)
        assertTrue(versionDirectory.mkdirs())
        fixture.manifest.components.forEach { component ->
            File(versionDirectory, component.file).writeBytes(Files.readAllBytes(fixture.file(component)))
        }
        Files.writeString(paths.regionDirectory(REGION).resolve(RegionPaths.CURRENT_FILE).toPath(), VERSION + "\n")
        val installedMap = versionDirectory.resolve("map.pmtiles")
        val corrupt = Files.readAllBytes(installedMap.toPath())
        corrupt[0] = (corrupt[0].toInt() xor 0xff).toByte()
        Files.write(installedMap.toPath(), corrupt)

        val staging = Files.createTempDirectory("grass-staging").toFile()
        try {
            FixturePackages.files().forEach { (name, bytes) -> File(staging, name).writeBytes(bytes) }
            val manifestBytes = FixturePackages.files().getValue(RegionIdentifiers.MANIFEST_FILE)
            val manifest =
                (RegionManifestParser.parse(manifestBytes, REGION, VERSION) as Outcome.Success).value
            val installer = RegionInstaller(paths, PackageVerifier())

            val result =
                installer.install(
                    request = request("https://example.test/package.zip"),
                    staging = staging,
                    manifest = manifest,
                    nonce = "nonce",
                    cancellation = CancellationHandle { true },
                )

            assertEquals(Outcome.Failure(AppError.Cancelled), result)
            assertTrue(installedMap.isFile)
            assertTrue(Files.readAllBytes(installedMap.toPath()).contentEquals(corrupt))
            assertEquals(VERSION, CurrentPointer.read(paths.regionDirectory(REGION)))
        } finally {
            staging.deleteRecursively()
        }
    }

    @Test
    fun aTruncatedZipIsRejected() =
        runTest {
            val zip = FixturePackages.zip(FixturePackages.files())
            val truncated = zip.copyOf(zip.size / 2)
            authenticatedPackageServer(token, truncated).use { server ->
                assertEquals(Outcome.Failure(AppError.CorruptData), download(server.baseUrl))
            }
            assertNoInstall()
        }

    @Test
    fun anUndeclaredExtraFileIsRejected() =
        runTest {
            val zip =
                FixturePackages.withManifest(
                    transform = { it },
                    extras = mapOf("extra.bin" to "not declared".toByteArray()),
                )
            authenticatedPackageServer(token, zip).use { server ->
                assertEquals(Outcome.Failure(AppError.CorruptData), download(server.baseUrl))
            }
            assertNoInstall()
        }

    @Test
    fun aZipSlipEntryIsRejectedAndWritesNothingOutsideTheStagingDirectory() =
        runTest {
            val entries = FixturePackages.files().toList() + ("../escape.txt" to "escaped".toByteArray())
            authenticatedPackageServer(token, FixturePackages.zipRaw(entries)).use { server ->
                assertEquals(Outcome.Failure(AppError.CorruptData), download(server.baseUrl))
            }

            assertFalse(root.toFile().walkTopDown().any { it.name == "escape.txt" })
            assertNoInstall()
        }

    @Test
    fun aManifestForAnotherRegionIsRejected() =
        runTest {
            val zip =
                FixturePackages.withManifest(
                    transform = { manifest ->
                        manifest.replace("\"region\": \"fixture-mini\"", "\"region\": \"ua-46\"")
                    },
                )
            authenticatedPackageServer(token, zip).use { server ->
                assertEquals(Outcome.Failure(AppError.CorruptData), download(server.baseUrl))
            }
            assertNoInstall()
        }

    @Test
    fun aBlockedSwitchNeverReachesTheServer() =
        runTest {
            authenticatedPackageServer(token, FixturePackages.zip(FixturePackages.files())).use { server ->
                val requested = AtomicReference<SwitchId>()
                val refusing =
                    object : NetworkAccess {
                        override suspend fun <T> request(
                            switch: SwitchId,
                            block: suspend () -> Outcome<T>,
                        ): Outcome<T> {
                            requested.set(switch)
                            return Outcome.Failure(AppError.SwitchOff(switch))
                        }
                    }

                val outcome =
                    RegionDownloader(HttpDownloadTransport(), paths, logs::add)
                        .download(request(server.baseUrl), policy(), refusing)

                assertEquals(Outcome.Failure(AppError.SwitchOff(SWITCH)), outcome)
                assertEquals(SWITCH, requested.get())
                assertEquals(0, server.requestCount)
                assertNoInstall()
            }
        }

    @Test
    fun theTokenNeverAppearsInTheLog() =
        runTest {
            authenticatedPackageServer(token, FixturePackages.zip(FixturePackages.files())).use { server ->
                success(download(server.baseUrl))
            }

            assertTrue(logs.any { it.contains("token=present") })
            assertFalse(logs.any { it.contains(token) })
            assertTrue(logs.any { it.contains("manifest region=fixture-mini") })
            assertTrue(logs.any { it.contains("installed region=fixture-mini") })
        }

    @Test
    fun anUnsafeRegionOrVersionIsInvalidInput() =
        runTest {
            val downloader = RegionDownloader(HttpDownloadTransport(), paths, logs::add)
            val unsafeRegion = request("http://127.0.0.1:1/package.zip").copy(region = "../escape")
            val unsafeVersion = request("http://127.0.0.1:1/package.zip").copy(version = "..")

            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                downloader.download(unsafeRegion, policy(), permissive()),
            )
            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                downloader.download(unsafeVersion, policy(), permissive()),
            )
        }

    private suspend fun download(
        baseUrl: String,
        token: String? = this.token,
        version: String = VERSION,
    ): Outcome<InstalledRegion> =
        RegionDownloader(HttpDownloadTransport(), paths, logs::add)
            .download(
                request = request("$baseUrl/package.zip", token, version),
                policy = policy(),
                access = permissive(),
            )

    private fun request(
        url: String,
        token: String? = this.token,
        version: String = VERSION,
    ): RegionRequest =
        RegionRequest(
            url = url,
            region = REGION,
            version = version,
            token = token?.let { BearerToken.of(it).value() },
        )

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

    /** A fixture-tagged package whose map bytes differ from the declared hash. */
    private fun corruptVariant(): ByteArray {
        val files = FixturePackages.files()
        val map = files.getValue("map.pmtiles").copyOf()
        map[0] = (map[0].toInt() xor 0xff).toByte()
        files["map.pmtiles"] = map
        val manifest = String(files.getValue(RegionIdentifiers.MANIFEST_FILE), Charsets.UTF_8)
        files[RegionIdentifiers.MANIFEST_FILE] =
            manifest.replace(FixturePackages.FIXTURE_VERSION, NEXT_VERSION).toByteArray(Charsets.UTF_8)
        return FixturePackages.zip(files)
    }

    private fun assertInstalledOnDisk() {
        val directory = paths.versionDirectory(REGION, VERSION)
        assertTrue(directory.isDirectory)
        assertEquals(VERSION, CurrentPointer.read(paths.regionDirectory(REGION)))
        val fixture = TestRegions.load()
        fixture.manifest.components.forEach { component ->
            assertTrue(File(directory, component.file).isFile)
        }
    }

    private fun assertNoInstall() {
        assertFalse(paths.regionDirectory(REGION).exists())
        assertFalse(paths.versionDirectory(REGION, VERSION).exists())
    }

    private fun success(outcome: Outcome<InstalledRegion>): InstalledRegion =
        when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("download failed: ${outcome.error}")
        }

    private fun <T> Outcome<T>.value(): T =
        when (this) {
            is Outcome.Success -> value
            is Outcome.Failure -> throw AssertionError("value refused: $error")
        }

    private companion object {
        const val REGION = "fixture-mini"
        const val VERSION = FixturePackages.FIXTURE_VERSION
        const val NEXT_VERSION = "2026-11-04-1"
        val SWITCH: SwitchId = SwitchId("network.region_updates")
    }
}
