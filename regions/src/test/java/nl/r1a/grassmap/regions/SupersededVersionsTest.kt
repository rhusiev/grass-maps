package nl.r1a.grassmap.regions

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class SupersededVersionsTest {
    private lateinit var root: Path

    @Before
    fun setUp() {
        root = Files.createTempDirectory("grass-superseded")
    }

    @After
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun theSweepKeepsOnlyTheVersionCurrentNames() {
        version("ua-46", "2026-10-01")
        version("ua-46", "2026-11-03")
        version("ua-46", "2026-09-01")
        point("ua-46", "2026-11-03")

        SupersededVersions.sweep(root.toFile())

        assertEquals(listOf("2026-11-03"), names("ua-46"))
        assertEquals("2026-11-03", CurrentPointer.read(File(root.toFile(), "ua-46")))
    }

    @Test
    fun aRegionWithoutAPointerIsLeftAlone() {
        version("ua-05", "2026-10-01")
        version("ua-05", "2026-11-03")

        SupersededVersions.sweep(root.toFile())

        assertEquals(listOf("2026-10-01", "2026-11-03"), names("ua-05"))
    }

    @Test
    fun aCrashBeforeThePointerFlipLosesNothingTheOldVersionNeeds() {
        version("ua-46", "2026-10-01")
        version("ua-46", "2026-11-03")
        point("ua-46", "2026-10-01")

        SupersededVersions.sweep(root.toFile())

        assertEquals(listOf("2026-10-01"), names("ua-46"))
        assertTrue(File(root.toFile(), "ua-46/2026-10-01/map.pmtiles").isFile)
    }

    @Test
    fun hiddenDirectoriesAreNotRegions() {
        File(root.toFile(), ".incoming/work").mkdirs()
        File(root.toFile(), ".routing/mount-x").mkdirs()

        SupersededVersions.sweep(root.toFile())

        assertTrue(File(root.toFile(), ".incoming/work").isDirectory)
        assertTrue(File(root.toFile(), ".routing/mount-x").isDirectory)
    }

    private fun version(
        region: String,
        version: String,
    ) {
        File(root.toFile(), "$region/$version").mkdirs()
        File(root.toFile(), "$region/$version/map.pmtiles").writeText("x")
    }

    private fun point(
        region: String,
        version: String,
    ) {
        File(root.toFile(), "$region/current").writeText("$version\n")
    }

    private fun names(region: String): List<String> =
        File(root.toFile(), region)
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .map { it.name }
            .sorted()
}
