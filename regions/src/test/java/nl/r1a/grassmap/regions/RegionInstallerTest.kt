package nl.r1a.grassmap.regions

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Route
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
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * The installer's commit point and crash window: a same-version reinstall
 * replaces the old copy, a failed move puts a quarantined current version back,
 * and the next install recovers the quarantine a kill left behind.
 */
class RegionInstallerTest {
    private lateinit var root: Path
    private lateinit var paths: RegionPaths

    @Before
    fun setUp() {
        root = Files.createTempDirectory("grass-installer")
        paths = RegionPaths(root.toFile())
    }

    @After
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun aSameVersionReinstallReplacesTheOldCopy() =
        runTest {
            assertFalse(install().value().reusedExisting)
            assertEquals(FIXTURE_IDS, installedFeatureIds())

            val replacement = install(FixturePackages.filesWithWayAlerts(geojson(8001))).value()

            assertFalse(replacement.reusedExisting)
            assertEquals(VERSION, CurrentPointer.read(paths.regionDirectory(REGION)))
            assertEquals(setOf(8001L), installedFeatureIds())
            assertNoQuarantine()
        }

    @Test
    fun aRepeatedReinstallReusesTheVerifiedCopy() =
        runTest {
            val files = FixturePackages.filesWithWayAlerts(geojson(8001))
            assertFalse(install(files).value().reusedExisting)

            assertTrue(install(files).value().reusedExisting)
            assertEquals(setOf(8001L), installedFeatureIds())
            assertNoQuarantine()
        }

    @Test
    fun aFailedMoveRestoresTheQuarantinedCurrentVersion() {
        install()
        corruptCurrentMap()
        val corrupted = Files.readAllBytes(targetMap().toPath())

        val result = install(mover = mover(failRollback = false))

        assertEquals(Outcome.Failure(AppError.Internal(INSTALL_ID)), result)
        assertEquals(VERSION, CurrentPointer.read(paths.regionDirectory(REGION)))
        assertTrue(Files.readAllBytes(targetMap().toPath()).contentEquals(corrupted))
        assertNoQuarantine()
    }

    @Test
    fun aFailedRollbackKeepsTheQuarantineForTheNextInstall() {
        install()
        corruptCurrentMap()
        val corrupted = Files.readAllBytes(targetMap().toPath())

        val result = install(nonce = "first", mover = mover(failRollback = true))

        assertEquals(Outcome.Failure(AppError.Internal(ROLLBACK_ID)), result)
        assertEquals(VERSION, CurrentPointer.read(paths.regionDirectory(REGION)))
        assertFalse(targetMap().exists())
        val quarantine = paths.quarantineDirectory(REGION, VERSION, "first")
        assertTrue(quarantine.isDirectory)
        assertTrue(Files.readAllBytes(quarantine.resolve("map.pmtiles").toPath()).contentEquals(corrupted))

        val recovered = install(nonce = "second").value()

        assertFalse(recovered.reusedExisting)
        assertEquals(VERSION, CurrentPointer.read(paths.regionDirectory(REGION)))
        assertTrue(targetMap().isFile)
        assertNoQuarantine()
    }

    @Test
    fun aCrashWindowQuarantineIsRestoredAndTheRegionStillReads() =
        runTest {
            install()
            val target = paths.versionDirectory(REGION, VERSION)
            val quarantine = paths.quarantineDirectory(REGION, VERSION, "crash")
            assertTrue(target.renameTo(quarantine))
            assertFalse(target.exists())
            assertEquals(VERSION, CurrentPointer.read(paths.regionDirectory(REGION)))

            val retried = install(nonce = "retry").value()

            assertTrue(retried.reusedExisting)
            assertTrue(target.isDirectory)
            assertFalse(quarantine.exists())
            assertEquals(FIXTURE_IDS, installedFeatureIds())
        }

    @Test
    fun aLegacyCrashWindowQuarantineIsRestored() =
        runTest {
            install()
            val target = paths.versionDirectory(REGION, VERSION)
            val legacy = File(paths.incomingDirectory(), "invalid-$VERSION-crash")
            assertTrue(target.renameTo(legacy))

            val retried = install(nonce = "retry").value()

            assertTrue(retried.reusedExisting)
            assertTrue(target.isDirectory)
            assertFalse(legacy.exists())
            assertEquals(FIXTURE_IDS, installedFeatureIds())
        }

    @Test
    fun aStaleQuarantineIsSwept() {
        install()
        val staleVersion = File(paths.incomingDirectory(), "invalid~$REGION~2026-11-01-0~stale")
        val staleCurrent = File(paths.incomingDirectory(), "invalid~$REGION~$VERSION~stale")
        listOf(staleVersion, staleCurrent).forEach { stale ->
            assertTrue(stale.mkdirs())
            File(stale, "junk.bin").writeText("stale")
        }

        install(nonce = "sweep").value()

        assertFalse(staleVersion.exists())
        assertFalse(staleCurrent.exists())
    }

    @Test
    fun anUpdateRemovesTheOldVersionAfterThePointerFlips() {
        install().value()
        assertEquals(setOf(VERSION), versionNames())

        install(filesAt(NEXT_VERSION), version = NEXT_VERSION).value()

        assertEquals(NEXT_VERSION, CurrentPointer.read(paths.regionDirectory(REGION)))
        assertEquals(setOf(NEXT_VERSION), versionNames())
    }

    @Test
    fun aCancelBeforeTheFlipKeepsTheOldVersionCurrent() {
        install().value()

        val outcome =
            install(
                filesAt(NEXT_VERSION),
                version = NEXT_VERSION,
                cancellation = CancellationHandle { true },
            )

        assertEquals(Outcome.Failure(AppError.Cancelled), outcome)
        assertEquals(VERSION, CurrentPointer.read(paths.regionDirectory(REGION)))
        assertEquals(setOf(VERSION), versionNames())
    }

    @Test
    fun aFailedMoveKeepsTheOldVersionCurrent() {
        install().value()

        val outcome = install(filesAt(NEXT_VERSION), version = NEXT_VERSION, mover = mover(failRollback = false))

        assertTrue(outcome is Outcome.Failure)
        assertEquals(VERSION, CurrentPointer.read(paths.regionDirectory(REGION)))
        assertEquals(setOf(VERSION), versionNames())
    }

    private fun filesAt(version: String): Map<String, ByteArray> =
        FixturePackages.files().also { files ->
            val manifest = String(files.getValue(RegionIdentifiers.MANIFEST_FILE), Charsets.UTF_8)
            files[RegionIdentifiers.MANIFEST_FILE] =
                manifest.replace(VERSION, version).toByteArray(Charsets.UTF_8)
        }

    private fun versionNames(): Set<String> =
        paths
            .regionDirectory(REGION)
            .listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.toSet()
            .orEmpty()

    private fun install(
        files: Map<String, ByteArray> = FixturePackages.files(),
        nonce: String = UUID.randomUUID().toString(),
        mover: ((File, File) -> Boolean)? = null,
        version: String = VERSION,
        cancellation: CancellationHandle = CancellationHandle.None,
    ): Outcome<InstalledRegion> {
        val manifest = manifestOf(files, version)
        val stagingDirectory = staging(files)
        val installer =
            if (mover == null) {
                RegionInstaller(paths, PackageVerifier())
            } else {
                RegionInstaller(paths, PackageVerifier(), mover)
            }
        return installer.install(
            request =
                RegionRequest(
                    url = "https://example.test/package.zip",
                    region = REGION,
                    version = version,
                    token = null,
                ),
            staging = stagingDirectory,
            manifest = manifest,
            nonce = nonce,
            cancellation = cancellation,
        )
    }

    private fun staging(files: Map<String, ByteArray>): File {
        val incoming = paths.incomingDirectory()
        assertTrue(incoming.isDirectory || incoming.mkdirs())
        val directory = File(incoming, "$STAGING_PREFIX${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        files.forEach { (name, bytes) -> File(directory, name).writeBytes(bytes) }
        return directory
    }

    private fun manifestOf(
        files: Map<String, ByteArray>,
        version: String = VERSION,
    ): RegionManifest =
        when (
            val parsed =
                RegionManifestParser.parse(
                    files.getValue(RegionIdentifiers.MANIFEST_FILE),
                    REGION,
                    version,
                )
        ) {
            is Outcome.Success -> parsed.value
            is Outcome.Failure -> throw AssertionError("the fixture manifest is invalid: ${parsed.error}")
        }

    /** Fails the staging move, like a full disk, and optionally the rollback too. */
    private fun mover(failRollback: Boolean): (File, File) -> Boolean =
        { source, target ->
            when {
                source.name.startsWith(STAGING_PREFIX) -> false
                failRollback && source.name.startsWith(RegionPaths.QUARANTINE_PREFIX) -> false
                else -> atomicMove(source, target)
            }
        }

    private fun atomicMove(
        source: File,
        target: File,
    ): Boolean =
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (exception: IOException) {
            false
        } catch (exception: SecurityException) {
            false
        }

    private fun corruptCurrentMap() {
        val map = targetMap()
        val bytes = Files.readAllBytes(map.toPath())
        bytes[0] = (bytes[0].toInt() xor 0xff).toByte()
        Files.write(map.toPath(), bytes)
    }

    private fun targetMap(): File = paths.versionDirectory(REGION, VERSION).resolve("map.pmtiles")

    private fun assertNoQuarantine() {
        val leftovers =
            paths
                .incomingDirectory()
                .listFiles()
                ?.filter { it.name.startsWith(RegionPaths.QUARANTINE_PREFIX) }
                .orEmpty()
        assertTrue("leftover quarantines: ${leftovers.map { it.name }}", leftovers.isEmpty())
    }

    private suspend fun installedFeatureIds(): Set<Long> {
        val reader = RegionReader(paths.versionDirectory(REGION, VERSION))
        return when (val outcome = reader.wayAlertFeatures(ROUTE, CORRIDOR_METERS)) {
            is Outcome.Success -> outcome.value.map { it.feature.osmId }.toSet()
            is Outcome.Failure -> throw AssertionError("the installed region did not read: ${outcome.error}")
        }
    }

    private fun Outcome<InstalledRegion>.value(): InstalledRegion =
        when (this) {
            is Outcome.Success -> value
            is Outcome.Failure -> throw AssertionError("install failed: $error")
        }

    private fun geojson(osmId: Long): String =
        """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","properties":{"kind":"ford","osmType":"node","osmId":$osmId},
           "geometry":{"type":"Point","coordinates":[23.9431,49.949]}}
        ]}
        """.trimIndent()

    private companion object {
        const val REGION = "fixture-mini"
        const val VERSION = FixturePackages.FIXTURE_VERSION
        const val NEXT_VERSION = "2026-12-01-1"
        const val INSTALL_ID = "region.install"
        const val ROLLBACK_ID = "region.install.rollback"
        const val STAGING_PREFIX = "staging-"
        const val CORRIDOR_METERS = 30.0
        val FIXTURE_IDS = setOf(9001L, 9002L, 9003L)
        val ROUTE =
            Route(
                geometry = listOf(GeoPoint(49.9490, 23.9430), GeoPoint(49.9490, 23.9530)),
                distanceMeters = 0.0,
                durationSeconds = 0.0,
            )
    }
}
