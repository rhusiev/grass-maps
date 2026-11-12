package nl.r1a.grassmap.plugins.contours

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.regions.RegionComponent
import nl.r1a.grassmap.regions.RegionManifest
import nl.r1a.grassmap.regions.RegionManifestParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The contour overlay reads the region `contours` component. The committed
 * fixture is the same real pipeline output the plugin tests read, so parsing it
 * through the production manifest reader is the component contract: kind,
 * PMTiles v3 format, z9-z14 levels and a digest that matches the bytes.
 */
class ContourComponentTest {
    private val packageDirectory = File(FIXTURE_DIRECTORY)
    private val manifestFile = File(packageDirectory, "manifest.json")

    @Test
    fun theFixtureManifestCarriesARealContoursComponent() {
        val manifest = parse()

        val contours = component(manifest)
        assertEquals("contours", contours.kind)
        assertEquals("contours.pmtiles", contours.file)
        assertEquals("pmtiles", contours.format)
        assertEquals(3, contours.formatVersion)
        assertEquals(listOf(9, 14), contours.zoomLevels)
        assertFalse("a stand-in must never be read as a real contour archive", contours.standIn)
        assertEquals(CONTOURS_BYTES, contours.bytes)
    }

    @Test
    fun theComponentDigestMatchesTheCommittedArchive() {
        val contours = component(parse())
        val archive = File(packageDirectory, contours.file)

        assertTrue("the contours archive is missing", archive.isFile)
        assertEquals(CONTOURS_BYTES, archive.length())
        assertEquals(contours.sha256, sha256(archive))
        assertEquals("PMTiles", archive.inputStream().use { input -> String(input.readNBytes(7)) })
    }

    @Test
    fun theManifestDeclaresContoursAsItsOnlyContourSource() {
        val manifest = parse()
        assertEquals(1, manifest.components.count { it.kind == "contours" })
    }

    @Test
    fun aManifestWithoutContoursParsesButHasNoContourComponent() {
        val without =
            manifestFile
                .readText()
                .replace(Regex("\"kind\": \"contours\""), "\"kind\": \"contours-absent\"")
                .toByteArray()

        val parsed =
            RegionManifestParser.parse(without, FIXTURE_REGION, FIXTURE_VERSION)
        when (parsed) {
            is Outcome.Success -> assertTrue(parsed.value.components.none { it.kind == "contours" })
            is Outcome.Failure -> assertEquals(AppError.CorruptData, parsed.error)
        }
    }

    @Test
    fun aContoursComponentWithOneZoomLevelIsRejected() {
        val broken =
            manifestFile
                .readText()
                .replace("\"zoomLevels\": [\n        9,\n        14\n      ]", "\"zoomLevels\": [9]")
                .toByteArray()

        assertEquals(
            Outcome.Failure(AppError.CorruptData),
            RegionManifestParser.parse(broken, FIXTURE_REGION, FIXTURE_VERSION),
        )
    }

    private fun parse(): RegionManifest =
        when (val parsed = RegionManifestParser.parse(manifestFile.readBytes(), FIXTURE_REGION, FIXTURE_VERSION)) {
            is Outcome.Success -> parsed.value
            is Outcome.Failure -> throw AssertionError("fixture manifest rejected: ${parsed.error}")
        }

    private fun component(manifest: RegionManifest): RegionComponent =
        requireNotNull(manifest.components.firstOrNull { it.kind == "contours" }) {
            "fixture manifest has no contours component"
        }

    private fun sha256(file: File): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val FIXTURE_DIRECTORY = "../../test-data/regions/fixture-mini/2026-11-03-1"
        const val FIXTURE_REGION = "fixture-mini"
        const val FIXTURE_VERSION = "2026-11-03-1"
        const val CONTOURS_BYTES = 23418L
    }
}
