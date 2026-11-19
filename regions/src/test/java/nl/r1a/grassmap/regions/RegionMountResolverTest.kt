package nl.r1a.grassmap.regions

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class RegionMountResolverTest {
    private val root: Path = Files.createTempDirectory("grass-region-mount")

    @After
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun aValidInstalledRegionMountsItsMapAndContours() {
        install()

        val mounts = mounts()

        assertTrue("expected one mount, got ${mounts.broken}", mounts.broken.isEmpty())
        val mounted = mounts.installed.single()
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
        val mounts = mounts()

        assertTrue(mounts.installed.isEmpty())
        assertTrue(mounts.broken.isEmpty())
    }

    @Test
    fun aRegionWithoutCurrentIsInvalid() {
        install()
        Files.delete(regionDirectory().resolve(RegionPaths.CURRENT_FILE))

        assertOnlyBroken(RegionMountProblem.MissingPointer)
    }

    @Test
    fun aCurrentPointerToAMissingVersionIsInvalid() {
        installRegion(regionDirectory(), "fixture-mini")
        Files.writeString(regionDirectory().resolve(RegionPaths.CURRENT_FILE), "2099-01-01-1\n")

        assertOnlyBroken(RegionMountProblem.MissingVersion("2099-01-01-1"))
    }

    @Test
    fun aCorruptCurrentPointerIsInvalid() {
        install()
        Files.writeString(regionDirectory().resolve(RegionPaths.CURRENT_FILE), "../2026-11-03-1\n")

        assertOnlyBroken(RegionMountProblem.CorruptPointer)
    }

    @Test
    fun aCorruptManifestIsInvalid() {
        install()
        Files.writeString(versionDirectory().resolve(RegionIdentifiers.MANIFEST_FILE), "{not json")

        assertOnlyBroken(RegionMountProblem.CorruptManifest)
    }

    @Test
    fun aManifestWithoutAMapComponentIsInvalid() {
        install(manifest = { it.replace(Regex("\"kind\":\\s*\"map\""), "\"kind\": \"mapx\"") })

        assertOnlyBroken(RegionMountProblem.MissingMap)
    }

    @Test
    fun aTruncatedMapIsCorrupt() {
        install()
        val map = versionDirectory().resolve("map.pmtiles")
        Files.write(map, ByteArray(16))

        assertOnlyBroken(RegionMountProblem.CorruptMap)
    }

    @Test
    fun aWrongPmtilesHeaderIsCorruptEvenAtTheDeclaredSize() {
        install()
        val map = versionDirectory().resolve("map.pmtiles")
        val bytes = Files.readAllBytes(map)
        bytes[0] = 'X'.code.toByte()
        Files.write(map, bytes)

        assertOnlyBroken(RegionMountProblem.CorruptMap)
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

        assertOnlyBroken(RegionMountProblem.CorruptMap)
    }

    @Test
    fun aCorruptContoursFileIsInvalidAndNeverCrash() {
        install()
        val contours = versionDirectory().resolve("contours.pmtiles")
        val bytes = Files.readAllBytes(contours)
        bytes[0] = 'X'.code.toByte()
        Files.write(contours, bytes)

        assertOnlyBroken(RegionMountProblem.CorruptContours)
    }

    @Test
    fun aDeclaredContoursFileThatIsMissingIsInvalid() {
        install()
        Files.delete(versionDirectory().resolve("contours.pmtiles"))

        assertOnlyBroken(RegionMountProblem.CorruptContours)
    }

    @Test
    fun everyInstalledRegionMountsTogether() {
        install()
        val second = root.resolve("ua-46")
        installRegion(second, region = "ua-46")

        val mounts = mounts()

        assertEquals(listOf("fixture-mini", "ua-46"), mounts.installed.map { it.region })
        assertTrue("no pack may report a problem: ${mounts.broken}", mounts.broken.isEmpty())
    }

    @Test
    fun aBrokenPackIsReportedWithoutHidingAWorkingOne() {
        install()
        val broken = root.resolve("ua-46").also { Files.createDirectories(it) }

        val mounts = mounts()

        assertEquals(listOf("fixture-mini"), mounts.installed.map { it.region })
        assertEquals(listOf("ua-46"), mounts.broken.map { it.region })
        assertEquals(RegionMountProblem.MissingPointer, mounts.broken.single().problem)
        assertTrue(Files.isDirectory(broken))
    }

    @Test
    fun aPackWithoutRoutingHasNoRoutingPart() {
        install()

        assertNull(mounts().installed.single().routing)
    }

    @Test
    fun aMountedPackReportsItsSearchSection() {
        install()

        val mounted = mounts().installed.single()
        val search = requireNotNull(mounted.search) { "the pack reports no search section" }

        assertEquals(versionDirectory().toFile(), search)
        assertTrue("the section carries its terms", File(search, "terms.fst").isFile)
        assertTrue("the section carries its features", File(search, "features.bin").isFile)
    }

    @Test
    fun aPackWithABrokenSearchSectionStillMountsItsMap() {
        install()
        Files.delete(versionDirectory().resolve("terms.fst"))

        val mounted = mounts().installed.single()

        assertNull("an incomplete section is not a search directory", mounted.search)
        assertTrue("a search problem must not hide the map", mounted.map.isFile)
    }

    @Test
    fun aRoutingTarIsALegacyArchive() {
        install { manifest -> asRouting(manifest, "routing.tar", directory = false) }

        assertEquals(RoutingPart.LegacyArchive, mounts().installed.single().routing)
    }

    @Test
    fun aRoutingDirectoryOfTheRightFormatIsMounted() {
        install { manifest -> asRouting(manifest, "contours.pmtiles", directory = true) }
        val routing = versionDirectory().resolve("contours.pmtiles")
        Files.delete(routing)
        Files.createDirectories(routing)

        val part = mounts().installed.single().routing

        assertEquals(routing.toFile(), (part as RoutingPart.Directory).directory)
    }

    @Test
    fun aRoutingDirectoryThatIsMissingIsUnusableAndTheMapStillMounts() {
        install { manifest -> asRouting(manifest, "contours.pmtiles", directory = true) }

        val mounted = mounts().installed.single()

        assertEquals(RoutingPart.Unusable, mounted.routing)
        assertTrue(mounted.map.isFile)
    }

    /**
     * Turns the contours component into a routing one. The mount reads the
     * manifest without verifying file hashes, so the stand-in file is enough.
     */
    private fun asRouting(
        manifest: String,
        file: String,
        directory: Boolean,
    ): String {
        val renamed =
            manifest
                .replace("\"kind\": \"contours\"", "\"kind\": \"routing\"")
                .replace("\"file\": \"contours.pmtiles\"", "\"file\": \"$file\"")
        if (!directory) {
            return renamed
        }
        val start = renamed.indexOf("\"kind\": \"routing\"")
        val end = renamed.indexOf("}", start)
        val block =
            renamed
                .substring(start, end)
                .replace("\"format\": \"pmtiles\"", "\"format\": \"${RoutingLayout.FORMAT}\", \"type\": \"directory\"")
                .replace("\"formatVersion\": 3", "\"formatVersion\": ${RoutingLayout.FORMAT_VERSION}")
        return renamed.substring(0, start) + block + renamed.substring(end)
    }

    private fun mounts(): RegionMounts = RegionMountResolver(root.toFile()).resolveAll()

    /** The store holds one region, and its problem is [problem]. */
    private fun assertOnlyBroken(problem: RegionMountProblem) {
        val mounts = mounts()
        assertTrue("expected no mount, got ${mounts.installed}", mounts.installed.isEmpty())
        assertEquals(listOf("fixture-mini"), mounts.broken.map { it.region })
        assertEquals(problem, mounts.broken.single().problem)
    }

    private fun regionDirectory(): Path = root.resolve("fixture-mini")

    private fun versionDirectory(): Path = regionDirectory().resolve(FixturePackages.FIXTURE_VERSION)

    private fun install(manifest: (String) -> String = { it }) {
        installRegion(regionDirectory(), "fixture-mini", manifest)
    }

    /**
     * Installs the fixture package as [region] under [directory], with the
     * manifest naming that region, so the store can hold two packs.
     */
    private fun installRegion(
        directory: Path,
        region: String,
        manifest: (String) -> String = { it },
    ) {
        val target = directory.resolve(FixturePackages.FIXTURE_VERSION)
        Files.createDirectories(target)
        FixturePackages.files().forEach { (name, bytes) ->
            val content =
                if (name == RegionIdentifiers.MANIFEST_FILE) {
                    manifest(String(bytes, Charsets.UTF_8))
                        .replace("\"region\": \"fixture-mini\"", "\"region\": \"$region\"")
                        .toByteArray(Charsets.UTF_8)
                } else {
                    bytes
                }
            Files.copy(
                byteArrayAsStream(content),
                target.resolve(name),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        Files.writeString(directory.resolve(RegionPaths.CURRENT_FILE), FixturePackages.FIXTURE_VERSION + "\n")
    }

    private fun byteArrayAsStream(bytes: ByteArray) = bytes.inputStream()
}
