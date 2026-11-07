package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.model.MapSourceIds
import nl.r1a.grassmap.regions.MountedRegion
import nl.r1a.grassmap.regions.RegionManifest
import nl.r1a.grassmap.regions.RegionMount
import nl.r1a.grassmap.regions.RegionMountProblem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class MapSourcesTest {
    private val root: Path = Files.createTempDirectory("grass-map-sources")
    private val external: Path = root.resolve("external").also { Files.createDirectories(it) }

    @After
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun anInstalledRegionWinsOverTheArchiveAndFixture() {
        val map = file("region/map.pmtiles")
        val contours = file("region/contours.pmtiles")
        val archive = file("external/${MapSources.LVIV_ARCHIVE}")
        val fixture = file("fixture.pmtiles")

        val sources =
            resolve(
                mount = { RegionMount.Mounted(mountedRegion(map, contours)) },
                fixture = { fixture.toFile() },
            )

        assertEquals(pmtiles(map), sources.uri(MapSourceIds.REGION))
        assertEquals(pmtiles(contours), sources.uri(MapSourceIds.CONTOURS))
        assertTrue(sources.hasContours)
        assertNull(sources.regionProblem)
        assertTrue(Files.isRegularFile(archive))
    }

    @Test
    fun anInstalledRegionWithoutContoursHasNoContourSource() {
        val map = file("region/map.pmtiles")
        file("external/${MapSources.LVIV_ARCHIVE}")

        val sources = resolve(mount = { RegionMount.Mounted(mountedRegion(map, null)) }, fixture = { null })

        assertEquals(pmtiles(map), sources.uri(MapSourceIds.REGION))
        assertNull(sources.uri(MapSourceIds.CONTOURS))
        assertFalse(sources.hasContours)
    }

    @Test
    fun withNoRegionTheWholeOblastArchiveWins() {
        val archive = file("external/${MapSources.LVIV_ARCHIVE}")
        val contours = file("external/${MapSources.CONTOURS_ARCHIVE}")
        val fixture = file("fixture.pmtiles")

        val sources = resolve(mount = { RegionMount.None }, fixture = { fixture.toFile() })

        assertEquals(pmtiles(archive), sources.uri(MapSourceIds.REGION))
        assertEquals(pmtiles(contours), sources.uri(MapSourceIds.CONTOURS))
        assertNull(sources.regionProblem)
    }

    @Test
    fun anInvalidRegionReportsTheProblemAndUsesTheArchive() {
        val archive = file("external/${MapSources.LVIV_ARCHIVE}")
        val fixture = file("fixture.pmtiles")

        val sources =
            resolve(
                mount = { RegionMount.Invalid("ua-46", RegionMountProblem.CorruptMap) },
                fixture = { fixture.toFile() },
            )

        assertEquals(RegionMountProblem.CorruptMap, sources.regionProblem)
        assertEquals("ua-46", sources.problemRegion)
        assertEquals(pmtiles(archive), sources.uri(MapSourceIds.REGION))
    }

    @Test
    fun anInvalidRegionWithoutAnArchiveFallsBackToTheFixture() {
        val fixture = file("fixture.pmtiles")

        val sources =
            resolve(
                mount = { RegionMount.Invalid("ua-46", RegionMountProblem.MissingPointer) },
                fixture = { fixture.toFile() },
            )

        assertEquals(RegionMountProblem.MissingPointer, sources.regionProblem)
        assertEquals(pmtiles(fixture), sources.uri(MapSourceIds.REGION))
    }

    @Test
    fun withNothingInstalledTheFixtureIsTheLastSource() {
        val fixture = file("fixture.pmtiles")

        val sources = resolve(mount = { RegionMount.None }, fixture = { fixture.toFile() })

        assertEquals(pmtiles(fixture), sources.uri(MapSourceIds.REGION))
        assertNull(sources.uri(MapSourceIds.CONTOURS))
        assertNull(sources.regionProblem)
    }

    @Test
    fun withNoSourceAtAllThereIsNoRegionUri() {
        val sources = resolve(mount = { RegionMount.None }, fixture = { null })

        assertNull(sources.uri(MapSourceIds.REGION))
        assertEquals("none", sources.regionName)
    }

    private fun resolve(
        mount: () -> RegionMount,
        fixture: () -> java.io.File?,
    ): MapSources =
        MapSources.resolve(
            regionsRoot = root.resolve("regions").toFile(),
            directories = listOf(external.toFile()),
            fixture = fixture,
            mount = { mount() },
        )

    private fun mountedRegion(
        map: Path,
        contours: Path?,
    ): MountedRegion =
        MountedRegion(
            region = "ua-46",
            version = "2026-11-05-bench",
            directory = root.toFile(),
            map = map.toFile(),
            contours = contours?.toFile(),
            manifest = RegionManifest("ua-46", "2026-11-05-bench", 1, "2026-11-05T00:00:00Z", null, emptyList()),
        )

    private fun file(relative: String): Path {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        if (!Files.exists(path)) {
            Files.write(path, byteArrayOf(1, 2, 3, 4))
        }
        return path
    }

    private fun pmtiles(path: Path): String = "pmtiles://file://${path.toFile().absolutePath}"
}
