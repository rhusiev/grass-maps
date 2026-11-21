package nl.r1a.grassmaps.regions

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.BoundingBox
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.pmtiles.PmtilesSpec
import nl.r1a.grassmaps.plugin.api.pmtiles.PmtilesWriter
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class RasterAreasTest {
    private val root: File = Files.createTempDirectory("grass-raster-areas").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun anInstalledAreaMountsWithItsBoundsTileCountAndLicence() {
        val installer = RasterPackInstaller(root)

        val installed = install(installer, tileCount = SMALL_TILE_COUNT)

        assertEquals(AREA_BOUNDS, installed.bounds)
        assertEquals(SMALL_TILE_COUNT.toLong(), installed.tileCount)
        assertEquals(ZOOM, installed.minZoom)
        assertEquals(ZOOM, installed.maxZoom)
        assertEquals(LICENCE, installed.metadata.licence)
        assertTrue(installed.metadata.isNonCommercial)
        assertEquals(listOf(AREA_ID), RegionMountResolver(root).rasterAreas().map { it.id })
    }

    @Test
    fun everyTileReadsBackBytePerByteWhenTheDirectorySplitsIntoLeaves() {
        val installed = install(RasterPackInstaller(root), tileCount = LEAF_TILE_COUNT)

        assertEquals(LEAF_TILE_COUNT.toLong(), installed.tileCount)
        for (index in 0 until LEAF_TILE_COUNT) {
            val (x, y) = position(index)
            assertArrayEquals("tile $x/$y", tileBytes(index), installed.tile(LEAF_ZOOM, x, y))
        }
        assertNull(installed.tile(LEAF_ZOOM, 0, LEAF_SIDE.toLong()))
    }

    @Test
    fun aRasterAreaIsNotAMapRegion() {
        install(RasterPackInstaller(root), tileCount = SMALL_TILE_COUNT)

        val mounts = RegionMountResolver(root).resolveAll()

        assertTrue(mounts.installed.isEmpty())
        assertTrue(mounts.broken.isEmpty())
    }

    @Test
    fun aTamperedPackDoesNotInstall() {
        val installer = RasterPackInstaller(root)
        val staging = stage(installer, SMALL_TILE_COUNT, SMALL_ZOOM)
        File(staging, RegionIdentifiers.RASTER_FILE).appendBytes(byteArrayOf(1))

        val result = runBlocking { installer.install(AREA_ID, VERSION, staging) }

        assertEquals(AppError.CorruptData, (result as Outcome.Failure).error)
        assertTrue(RegionMountResolver(root).rasterAreas().isEmpty())
    }

    @Test
    fun aPackWithAnExtraFileDoesNotInstall() {
        val installer = RasterPackInstaller(root)
        val staging = stage(installer, SMALL_TILE_COUNT, SMALL_ZOOM)
        File(staging, "extra.bin").writeBytes(byteArrayOf(1))

        assertTrue(runBlocking { installer.install(AREA_ID, VERSION, staging) } is Outcome.Failure)
    }

    @Test
    fun aRegionIdCannotInstallThroughTheRasterInstaller() {
        val installer = RasterPackInstaller(root)
        val staging = stage(installer, SMALL_TILE_COUNT, SMALL_ZOOM)

        val result = runBlocking { installer.install("ua-46", VERSION, staging) }

        assertEquals(AppError.InvalidInput, (result as Outcome.Failure).error)
    }

    @Test
    fun aSecondVersionReplacesTheFirstAndRemoveDeletesTheArea() {
        val installer = RasterPackInstaller(root)
        install(installer, SMALL_TILE_COUNT)

        val second = install(installer, SMALL_TILE_COUNT, version = "v2")

        assertEquals("v2", second.version)
        assertEquals(listOf("v2"), RegionMountResolver(root).rasterAreas().map { it.version })
        assertTrue(runBlocking { installer.remove(AREA_ID) } is Outcome.Success)
        assertFalse(File(root, AREA_ID).exists())
        assertTrue(RegionMountResolver(root).rasterAreas().isEmpty())
    }

    @Test
    fun theManifestParserRefusesARasterPackageWithAFeaturesComponent() {
        val manifest =
            manifestJson(AREA_ID, VERSION, kind = "features", file = "features.sqlite", format = "place-features")

        val result = RegionManifestParser.parse(manifest.toByteArray(), AREA_ID, VERSION)

        assertTrue(result is Outcome.Failure)
    }

    @Test
    fun metadataWithoutALicenceIsNotAMountedArea() {
        assertNull(RasterAreaMetadata.parse("""{"name":"x"}"""))
        assertNull(RasterAreaMetadata.parse("not json"))
        assertNotNull(RasterAreaMetadata.parse(metadataJson()))
    }

    @Test
    fun aPackWhoseMetadataCannotMountNeverReplacesAGoodArea() {
        val installer = RasterPackInstaller(root)
        install(installer, SMALL_TILE_COUNT)
        val staging = stage(installer, SMALL_TILE_COUNT, SMALL_ZOOM, version = "v2", metadata = "{}")

        val result = runBlocking { installer.install(AREA_ID, "v2", staging) }

        assertEquals(AppError.CorruptData, (result as Outcome.Failure).error)
        assertEquals(listOf(VERSION), RegionMountResolver(root).rasterAreas().map { it.version })
        assertFalse(File(root, "$AREA_ID/v2").exists())
    }

    @Test
    fun aBadIdOrVersionCreatesNoStagingDirectory() {
        val installer = RasterPackInstaller(root)

        for ((id, version) in listOf("ua-46" to VERSION, "raster-../x" to VERSION, "raster-" to VERSION, AREA_ID to "../v")) {
            assertEquals(AppError.InvalidInput, (installer.newStagingDirectory(id, version) as Outcome.Failure).error)
        }
        assertFalse(File(root, ".incoming").exists())
    }

    @Test
    fun startupSweepDeletesRasterLeftoversAndNothingElse() {
        val installer = RasterPackInstaller(root)
        install(installer, SMALL_TILE_COUNT)
        val killedStaging = stage(installer, SMALL_TILE_COUNT, SMALL_ZOOM, version = "v2")
        val orphanVersion = File(root, "$AREA_ID/v9").also { it.mkdirs() }
        val pointerless = File(root, "raster-eox-s2-2016-ffffffff/v1").also { it.mkdirs() }
        val regionPartial = File(root, ".incoming/partial~ua-46~v1").also { it.mkdirs() }
        val regionDirectory = File(root, "ua-46/v1").also { it.mkdirs() }

        runBlocking { installer.sweepLeftovers() }

        assertFalse(killedStaging.exists())
        assertFalse(orphanVersion.exists())
        assertFalse(pointerless.parentFile.exists())
        assertTrue(regionPartial.isDirectory)
        assertTrue(regionDirectory.isDirectory)
        assertEquals(listOf(VERSION), RegionMountResolver(root).rasterAreas().map { it.version })
    }

    @Test
    fun anInstallWaitsForTheSharedInstallLock() =
        runBlocking {
            val lock = InstallLock()
            val installer = RasterPackInstaller(root, lock)
            val staging = stage(installer, SMALL_TILE_COUNT, SMALL_ZOOM)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder = launch { lock.withLock { entered.complete(Unit).also { release.await() } } }
            entered.await()
            val installing = async(Dispatchers.Default) { installer.install(AREA_ID, VERSION, staging) }

            val early = withTimeoutOrNull(LOCK_WAIT_MS) { installing.await() }
            release.complete(Unit)
            holder.join()

            assertNull("the install must wait for the lock", early)
            assertTrue(installing.await() is Outcome.Success)
        }

    private fun install(
        installer: RasterPackInstaller,
        tileCount: Int,
        version: String = VERSION,
    ): MountedRaster {
        val zoom = if (tileCount > SMALL_TILE_COUNT) LEAF_ZOOM else SMALL_ZOOM
        val result = runBlocking { installer.install(AREA_ID, version, stage(installer, tileCount, zoom, version)) }
        return (result as Outcome.Success).value
    }

    private fun stage(
        installer: RasterPackInstaller,
        tileCount: Int,
        zoom: Int,
        version: String = VERSION,
        metadata: String = metadataJson(),
    ): File {
        val staging = (installer.newStagingDirectory(AREA_ID, version) as Outcome.Success).value
        val pack = File(staging, RegionIdentifiers.RASTER_FILE)
        PmtilesWriter(File(staging, "data.tmp")).use { writer ->
            for (index in 0 until tileCount) {
                val (x, y) = position(index, 1L shl zoom)
                writer.addTile(zoom, x, y, tileBytes(index))
            }
            writer.finish(pack, PmtilesSpec(TILE_TYPE_JPEG, AREA_BOUNDS, metadata))
        }
        File(staging, "data.tmp").delete()
        File(staging, RegionIdentifiers.MANIFEST_FILE).writeText(manifestFor(pack, version))
        return staging
    }

    private fun position(
        index: Int,
        side: Long = LEAF_SIDE.toLong(),
    ): Pair<Long, Long> = (index % side) to (index / side)

    private fun tileBytes(index: Int): ByteArray = "tile-$index".toByteArray()

    private fun metadataJson(): String =
        """{"name":"Area","grassmaps:source":"eox-s2-2018","attribution":"EOX",""" +
            """"grassmaps:licence":"$LICENCE","grassmaps:licenceUrl":"https://example.test/l",""" +
            """"grassmaps:nonCommercial":true}"""

    private fun manifestFor(
        pack: File,
        version: String,
    ): String =
        manifestJson(
            AREA_ID,
            version,
            kind = RegionIdentifiers.RASTER_KIND,
            file = RegionIdentifiers.RASTER_FILE,
            format = "pmtiles",
            bytes = pack.length(),
            sha256 = sha256(pack),
        )

    private fun manifestJson(
        region: String,
        version: String,
        kind: String,
        file: String,
        format: String,
        bytes: Long = 10,
        sha256: String = "0".repeat(RegionIdentifiers.SHA256_HEX_LENGTH),
    ): String =
        """{"region":"$region","version":"$version","packageFormat":1,"osmTimestamp":"2026-11-17T00:00:00Z",""" +
            """"components":[{"kind":"$kind","file":"$file","format":"$format","formatVersion":3,""" +
            """"version":"1","bytes":$bytes,"sha256":"$sha256"}]}"""

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val LOCK_WAIT_MS = 300L
        const val AREA_ID = "raster-eox-s2-2018-0a1b2c3d"
        const val VERSION = "v1"
        const val LICENCE = "CC BY-NC-SA 4.0"
        const val TILE_TYPE_JPEG = 3
        const val SMALL_ZOOM = 4
        const val ZOOM = SMALL_ZOOM
        const val SMALL_TILE_COUNT = 12
        const val LEAF_ZOOM = 6
        const val LEAF_SIDE = 64
        const val LEAF_TILE_COUNT = 1500
        val AREA_BOUNDS = BoundingBox(GeoPoint(49.5, 23.5), GeoPoint(50.0, 24.5))
    }
}
