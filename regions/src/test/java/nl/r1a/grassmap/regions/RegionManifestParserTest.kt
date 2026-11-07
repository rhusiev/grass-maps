package nl.r1a.grassmap.regions

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class RegionManifestParserTest {
    private val fixture = FixturePackages.files().getValue(RegionIdentifiers.MANIFEST_FILE)

    @Test
    fun theCommittedFixtureParses() {
        val manifest = success(parse(fixture))

        assertEquals("fixture-mini", manifest.region)
        assertEquals(FixturePackages.FIXTURE_VERSION, manifest.version)
        assertEquals(RegionIdentifiers.SUPPORTED_PACKAGE_FORMAT, manifest.packageFormat)
        assertEquals("2026-11-03T21:24:23Z", manifest.osmTimestamp)
        assertEquals(4, manifest.components.size)
        assertEquals(listOf(23.94, 49.94, 23.96, 49.96), manifest.pipeline?.bbox)
        assertEquals(
            setOf("ukraine-final.pmtiles", "lviv-90m-20m.pmtiles"),
            manifest.pipeline
                ?.sources
                ?.map { it.archive }
                ?.toSet(),
        )
        val map = manifest.components.single { it.kind == "map" }
        assertEquals("map.pmtiles", map.file)
        assertEquals(90141, map.bytes)
        assertEquals("c29ca3203c4d4c7e4c52e5a505fbc56f365d1c4a980a80c280273ce82fdd722c", map.sha256)
        assertEquals(listOf(12, 14), map.zoomLevels)
    }

    @Test
    fun aNewerPackageFormatIsUnsupported() {
        val json = rewritten("\"packageFormat\": 1", "\"packageFormat\": 2")

        assertEquals(Outcome.Failure(AppError.Unsupported), parse(json))
    }

    @Test
    fun aManifestForAnotherVersionIsCorrupt() {
        assertEquals(
            Outcome.Failure(AppError.CorruptData),
            RegionManifestParser.parse(fixture, "fixture-mini", "2026-11-04-1"),
        )
    }

    @Test
    fun aManifestForAnotherRegionIsCorrupt() {
        assertEquals(
            Outcome.Failure(AppError.CorruptData),
            RegionManifestParser.parse(fixture, "ua-46", FixturePackages.FIXTURE_VERSION),
        )
    }

    @Test
    fun anUnsafeComponentNameIsCorrupt() {
        val json = rewritten("\"file\": \"map.pmtiles\"", "\"file\": \"../map.pmtiles\"")

        assertEquals(Outcome.Failure(AppError.CorruptData), parse(json))
    }

    @Test
    fun thePointerFileNameIsNotAValidVersion() {
        assertFalse(RegionIdentifiers.isSafeSegment(RegionPaths.CURRENT_FILE))

        val json = FixturePackages.atVersion(RegionPaths.CURRENT_FILE)
        assertEquals(
            Outcome.Failure(AppError.CorruptData),
            RegionManifestParser.parse(json, "fixture-mini", RegionPaths.CURRENT_FILE),
        )
    }

    @Test
    fun aDuplicateComponentFileIsCorrupt() {
        val json = rewritten("\"file\": \"contours.pmtiles\"", "\"file\": \"map.pmtiles\"")

        assertEquals(Outcome.Failure(AppError.CorruptData), parse(json))
    }

    @Test
    fun bytesAndHashesAreCheckedBeforeAnythingIsBelieved() {
        val region = FixturePackages.loadedRegion()
        region.manifest.components.forEach { component ->
            assertTrue("${component.file} bytes", component.bytes > 0)
            assertTrue("${component.file} sha", RegionIdentifiers.isSha256(component.sha256))
        }
    }

    @Test
    fun noFixtureComponentEscapesThePackageDirectory() {
        val root: Path = FixturePackages.loadedRegion().root
        FixturePackages.loadedRegion().manifest.components.forEach { component ->
            assertFalse(Files.isSymbolicLink(root.resolve(component.file)))
        }
    }

    private fun rewritten(
        from: String,
        to: String,
    ): ByteArray = String(fixture, Charsets.UTF_8).replace(from, to).toByteArray(Charsets.UTF_8)

    private fun parse(bytes: ByteArray): Outcome<RegionManifest> =
        RegionManifestParser.parse(bytes, "fixture-mini", FixturePackages.FIXTURE_VERSION)

    private fun success(outcome: Outcome<RegionManifest>): RegionManifest =
        when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("parse failed: ${outcome.error}")
        }
}
