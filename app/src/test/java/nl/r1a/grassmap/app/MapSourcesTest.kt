package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.model.MapSourceIds
import nl.r1a.grassmap.plugin.api.testing.fixture.TestRegions
import nl.r1a.grassmap.regions.RegionMountProblem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The logical map sources over a real region store: the committed fixture
 * package is a real archive, so the tile source path runs end to end here.
 */
class MapSourcesTest {
    private val root: Path = Files.createTempDirectory("grass-map-sources")
    private val external: Path = root.resolve("external").also { Files.createDirectories(it) }
    private val store: Path = root.resolve("regions").also { Files.createDirectories(it) }

    @After
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun anInstalledPackBecomesOneLogicalSourcePerKind() {
        install("ua-46")

        val sources = resolve()

        assertNull("an installed pack is not loaded by URI", sources.uri(MapSourceIds.REGION))
        assertNull(sources.uri(MapSourceIds.CONTOURS))
        assertNotNull(sources.tileSources[MapSourceIds.REGION])
        assertNotNull(sources.tileSources[MapSourceIds.CONTOURS])
        assertTrue(sources.hasContours)
        assertNull(sources.regionProblem)
        assertEquals("region:ua-46/${TestRegions.DEFAULT_VERSION}", sources.regionName)
    }

    @Test
    fun worldAndRegionShareTheMapTiles() {
        install("ua-46")

        val sources = resolve()

        // `world` and `region` are two zoom bands over one set of map
        // components, so they read one source and no tile is served twice.
        assertSame(sources.tileSources[MapSourceIds.WORLD], sources.tileSources[MapSourceIds.REGION])
    }

    @Test
    fun theInstalledPackServesItsTilesThroughTheMapSources() {
        install("ua-46")

        val sources = resolve()

        val map = requireNotNull(sources.tileSources.getValue(MapSourceIds.REGION).tile(TILE_ZOOM, TILE_X, TILE_Y))
        assertTrue("a served tile carries the places layer", String(map, Charsets.ISO_8859_1).contains("places"))
        val contours = requireNotNull(sources.tileSources.getValue(MapSourceIds.CONTOURS).tile(TILE_ZOOM, TILE_X, TILE_Y))
        assertTrue("a served contour tile carries the contours layer", String(contours, Charsets.ISO_8859_1).contains("contours"))
    }

    @Test
    fun twoPacksAreNamedTogetherAndServeOneSourcePerKind() {
        install("ua-46")
        install("ua-61")

        val sources = resolve()

        assertEquals("regions:ua-46,ua-61", sources.regionName)
        assertNotNull(sources.tileSources[MapSourceIds.REGION])
        assertTrue(sources.hasContours)
    }

    @Test
    fun aBrokenPackIsReportedAndTheGoodOneStillServes() {
        install("ua-46")
        breakPack("ua-61")

        val sources = resolve()

        assertEquals(RegionMountProblem.MissingVersion(MISSING_VERSION), sources.regionProblem)
        assertEquals("ua-61", sources.problemRegion)
        assertEquals("region:ua-46/${TestRegions.DEFAULT_VERSION}", sources.regionName)
        assertNotNull(sources.tileSources[MapSourceIds.REGION])
    }

    @Test
    fun withNoPackTheWholeOblastArchiveWins() {
        val archive = file("external/${MapSources.LVIV_ARCHIVE}")
        val contours = file("external/${MapSources.CONTOURS_ARCHIVE}")

        val sources = resolve(fixture = { null })

        assertEquals(pmtiles(archive), sources.uri(MapSourceIds.REGION))
        assertEquals(pmtiles(contours), sources.uri(MapSourceIds.CONTOURS))
        assertTrue(sources.tileSources.isEmpty())
        assertNull(sources.regionProblem)
    }

    @Test
    fun withNoPackAndNoArchiveTheFixtureIsTheLastSource() {
        val fixture = file("fixture.pmtiles")

        val sources = resolve(fixture = { fixture.toFile() }, regionsRoot = null)

        assertEquals(pmtiles(fixture), sources.uri(MapSourceIds.REGION))
        assertNull(sources.uri(MapSourceIds.CONTOURS))
        assertNull(sources.uri(MapSourceIds.WORLD))
    }

    @Test
    fun withNothingInstalledThereIsNoSourceAtAll() {
        val sources = resolve(fixture = { null })

        assertNull(sources.uri(MapSourceIds.REGION))
        assertTrue(sources.tileSources.isEmpty())
        assertEquals("none", sources.regionName)
    }

    @Test
    fun aBrokenPackWithNoOtherSourceReportsTheProblemAndFallsBack() {
        val fixture = file("fixture.pmtiles")
        breakPack("ua-46")

        val sources = resolve(fixture = { fixture.toFile() })

        assertEquals(RegionMountProblem.MissingVersion(MISSING_VERSION), sources.regionProblem)
        assertEquals("ua-46", sources.problemRegion)
        assertEquals(pmtiles(fixture), sources.uri(MapSourceIds.REGION))
        assertTrue(sources.tileSources.isEmpty())
    }

    @Test
    fun aPushedContourArchiveIsNotReplacedByTheFixtureOne() {
        file("external/${MapSources.LVIV_ARCHIVE}")
        val contours = file("external/${MapSources.CONTOURS_ARCHIVE}")
        val fixtureContours = file("fixture-contours.pmtiles")

        val sources =
            resolve(
                fixture = { file("fixture.pmtiles").toFile() },
                contoursFixture = { fixtureContours.toFile() },
                regionsRoot = null,
            )

        assertEquals(pmtiles(contours), sources.uri(MapSourceIds.CONTOURS))
    }

    /**
     * Installs the committed fixture package under [region], copied into the
     * temporary store so nothing can delete the repository's test data.
     */
    private fun install(region: String) {
        val source = TestRegions.load().root
        val target = Files.createDirectories(store.resolve(region).resolve(TestRegions.DEFAULT_VERSION))
        Files.list(source).use { entries ->
            entries.forEach { file ->
                val name = file.fileName.toString()
                val bytes = Files.readAllBytes(file)
                val content =
                    if (name == "manifest.json") {
                        String(bytes, Charsets.UTF_8)
                            .replace("\"region\": \"${TestRegions.DEFAULT_REGION}\"", "\"region\": \"$region\"")
                            .toByteArray(Charsets.UTF_8)
                    } else {
                        bytes
                    }
                Files.write(target.resolve(name), content)
            }
        }
        Files.writeString(store.resolve(region).resolve("current"), TestRegions.DEFAULT_VERSION + "\n")
    }

    /** An installed region whose `current` pointer names a version that is not there. */
    private fun breakPack(region: String) {
        val directory = Files.createDirectories(store.resolve(region))
        Files.writeString(directory.resolve("current"), "$MISSING_VERSION\n")
    }

    private fun resolve(
        fixture: () -> java.io.File? = { null },
        contoursFixture: () -> java.io.File? = { null },
        regionsRoot: Path? = store,
    ): MapSources =
        MapSources.resolve(
            regionsRoot = regionsRoot?.toFile(),
            directories = listOf(external.toFile()),
            fixture = fixture,
            contoursFixture = contoursFixture,
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

    private companion object {
        const val MISSING_VERSION = "2099-01-01-1"

        /** A z13 tile over the committed fixture's own bounding box. */
        const val TILE_ZOOM = 13
        const val TILE_X = 4640L
        const val TILE_Y = 2780L
    }
}
