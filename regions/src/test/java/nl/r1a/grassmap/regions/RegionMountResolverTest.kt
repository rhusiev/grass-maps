package nl.r1a.grassmap.regions

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class RegionMountResolverTest {
    private val fixture = FixturePackages.loadedRegion()
    private val root: Path = Files.createTempDirectory("grass-region-mount")

    @After
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun aValidInstalledRegionMountsItsMapAndContours() {
        install()

        val mount = resolve()

        assertTrue("expected a mount, got $mount", mount is RegionMount.Mounted)
        val mounted = (mount as RegionMount.Mounted).region
        assertEquals("fixture-mini", mounted.region)
        assertEquals(FixturePackages.FIXTURE_VERSION, mounted.version)
        assertTrue(mounted.map.isFile)
        assertTrue("contours must mount too", mounted.contours?.isFile == true)
        assertEquals(
            "map.pmtiles",
            mounted.map.name,
        )
    }

    @Test
    fun anEmptyStoreHasNoRegion() {
        assertEquals(RegionMount.None, resolve())
    }

    @Test
    fun aRegionWithoutCurrentIsInvalid() {
        install()
        Files.delete(regionDirectory().resolve(RegionPaths.CURRENT_FILE))

        assertEquals(
            RegionMount.Invalid("fixture-mini", RegionMountProblem.MissingPointer),
            resolve(),
        )
    }

    @Test
    fun aCurrentPointerToAMissingVersionIsInvalid() {
        copyPackage()
        Files.writeString(regionDirectory().resolve(RegionPaths.CURRENT_FILE), "2099-01-01-1\n")

        assertEquals(
            RegionMount.Invalid("fixture-mini", RegionMountProblem.MissingVersion("2099-01-01-1")),
            resolve(),
        )
    }

    @Test
    fun aCorruptCurrentPointerIsInvalid() {
        install()
        Files.writeString(regionDirectory().resolve(RegionPaths.CURRENT_FILE), "../2026-11-03-1\n")

        assertEquals(
            RegionMount.Invalid("fixture-mini", RegionMountProblem.CorruptPointer),
            resolve(),
        )
    }

    @Test
    fun aCorruptManifestIsInvalid() {
        install()
        Files.writeString(versionDirectory().resolve(RegionIdentifiers.MANIFEST_FILE), "{not json")

        assertEquals(
            RegionMount.Invalid("fixture-mini", RegionMountProblem.CorruptManifest),
            resolve(),
        )
    }

    @Test
    fun aManifestWithoutAMapComponentIsInvalid() {
        install(manifest = { it.replace(Regex("\"kind\":\\s*\"map\""), "\"kind\": \"mapx\"") })

        assertEquals(
            RegionMount.Invalid("fixture-mini", RegionMountProblem.MissingMap),
            resolve(),
        )
    }

    @Test
    fun aTruncatedMapIsCorrupt() {
        install()
        val map = versionDirectory().resolve("map.pmtiles")
        Files.write(map, ByteArray(16))

        assertEquals(
            RegionMount.Invalid("fixture-mini", RegionMountProblem.CorruptMap),
            resolve(),
        )
    }

    @Test
    fun aWrongPmtilesHeaderIsCorruptEvenAtTheDeclaredSize() {
        install()
        val map = versionDirectory().resolve("map.pmtiles")
        val bytes = Files.readAllBytes(map)
        bytes[0] = 'X'.code.toByte()
        Files.write(map, bytes)

        assertEquals(
            RegionMount.Invalid("fixture-mini", RegionMountProblem.CorruptMap),
            resolve(),
        )
    }

    @Test
    fun aHeaderThatPointsPastTheEndIsCorrupt() {
        install()
        val map = versionDirectory().resolve("map.pmtiles")
        val bytes = Files.readAllBytes(map)
        // Root directory offset (little-endian u64 at byte 8) beyond the file.
        val beyond = bytes.size.toLong() + 1L
        for (index in 0 until 8) {
            bytes[8 + index] = ((beyond shr (8 * index)) and 0xFF).toByte()
        }
        Files.write(map, bytes)

        assertEquals(
            RegionMount.Invalid("fixture-mini", RegionMountProblem.CorruptMap),
            resolve(),
        )
    }

    @Test
    fun aCorruptContoursFileIsInvalidAndNeverCrash() {
        install()
        val contours = versionDirectory().resolve("contours.pmtiles")
        val bytes = Files.readAllBytes(contours)
        bytes[0] = 'X'.code.toByte()
        Files.write(contours, bytes)

        assertEquals(
            RegionMount.Invalid("fixture-mini", RegionMountProblem.CorruptContours),
            resolve(),
        )
    }

    @Test
    fun aDeclaredContoursFileThatIsMissingIsInvalid() {
        install()
        Files.delete(versionDirectory().resolve("contours.pmtiles"))

        assertEquals(
            RegionMount.Invalid("fixture-mini", RegionMountProblem.CorruptContours),
            resolve(),
        )
    }

    @Test
    fun moreThanOneInstalledRegionIsReportedNotGuessed() {
        install()
        val second = root.resolve("ua-46")
        Files.createDirectories(second)

        assertEquals(
            RegionMount.Invalid(null, RegionMountProblem.MultipleRegions(listOf("fixture-mini", "ua-46"))),
            resolve(),
        )
    }

    private fun resolve(): RegionMount = RegionMountResolver(root.toFile()).resolve()

    private fun regionDirectory(): Path = root.resolve("fixture-mini")

    private fun versionDirectory(): Path = regionDirectory().resolve(FixturePackages.FIXTURE_VERSION)

    private fun install(manifest: (String) -> String = { it }) {
        copyPackage(manifest)
        Files.writeString(regionDirectory().resolve(RegionPaths.CURRENT_FILE), FixturePackages.FIXTURE_VERSION + "\n")
    }

    private fun copyPackage(manifest: (String) -> String = { it }) {
        val target = versionDirectory()
        Files.createDirectories(target)
        FixturePackages.files().forEach { (name, bytes) ->
            val content = if (name == RegionIdentifiers.MANIFEST_FILE) manifest(String(bytes, Charsets.UTF_8)).toByteArray() else bytes
            Files.copy(
                byteArrayAsStream(content),
                target.resolve(name),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun byteArrayAsStream(bytes: ByteArray) = bytes.inputStream()
}
