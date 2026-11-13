package nl.r1a.grassmap.regions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.testing.fixture.TestRegions
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
        assertEquals(13, manifest.components.size)
        assertEquals(listOf(23.94, 49.94, 23.96, 49.96), manifest.pipeline?.bbox)
        assertEquals(
            setOf("ukraine-final.pmtiles", "lviv-90m-10m.pmtiles", "way-alerts.osm.pbf", "route-relations.osm.pbf"),
            manifest.pipeline
                ?.sources
                ?.map { it.archive }
                ?.toSet(),
        )
        val wayAlertSource = manifest.pipeline?.sources?.single { it.role == "wayAlertSource" }
        assertEquals("way-alerts.osm.pbf", wayAlertSource?.archive)
        assertEquals(
            "041e431574f322a7fdfe385ff83635135d6cd905efd34cfefc8c7200469ef8cc",
            wayAlertSource?.sha256,
        )
        val map = manifest.components.single { it.kind == "map" }
        assertEquals("map.pmtiles", map.file)
        assertEquals(90141, map.bytes)
        assertEquals("c29ca3203c4d4c7e4c52e5a505fbc56f365d1c4a980a80c280273ce82fdd722c", map.sha256)
        assertEquals(listOf(12, 14), map.zoomLevels)
        val features = manifest.components.single { it.kind == "features" }
        assertEquals("place-features", features.format)
        assertEquals(1, features.formatVersion)
    }

    @Test
    fun theLvivPackManifestParsesWithItsWayAlertProvenance() {
        val file =
            TestRegions.repositoryRoot().resolve("test-data/regions/lviv-way-alerts/pack-manifest.json")
        val manifest = success(RegionManifestParser.parse(Files.readAllBytes(file), "ua-46", "2026-11-12-1"))

        assertEquals(13, manifest.components.size)
        val wayAlerts = manifest.components.single { it.kind == "way-alerts" }
        assertEquals(2_921_155L, wayAlerts.bytes)
        assertEquals("c19a15e26dd0e009f87d69ae4afbb9454369d0d183ada147c37ee9f199b5303d", wayAlerts.sha256)
        assertFalse(wayAlerts.standIn)
        val source = manifest.pipeline?.sources?.single { it.role == "wayAlertSource" }
        assertEquals("ukraine-latest.osm.pbf", source?.archive)
        assertEquals("ff46165a08b21fc4271aa6d5f199e55d0c9b786bcfd8f50e0f7c31f82248347f", source?.sha256)
    }

    @Test
    fun aManifestWithoutFeaturesIsCorrupt() {
        val json = rewrittenComponents { components -> components.filterNot { kindOf(it) == "features" } }

        assertEquals(Outcome.Failure(AppError.CorruptData), parse(json))
    }

    @Test
    fun aFeaturesComponentWithAnotherFormatVersionIsCorrupt() {
        val json =
            rewrittenComponents { components ->
                components.map { component ->
                    if (kindOf(component) == "features") {
                        JsonObject(component + ("formatVersion" to JsonPrimitive(2)))
                    } else {
                        component
                    }
                }
            }

        assertEquals(Outcome.Failure(AppError.CorruptData), parse(json))
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

    /** The fixture manifest with its component array passed through [transform]. */
    private fun rewrittenComponents(transform: (List<JsonObject>) -> List<JsonObject>): ByteArray {
        val root = Json.parseToJsonElement(String(fixture, Charsets.UTF_8)).jsonObject
        val components = root.getValue("components").jsonArray.map { it.jsonObject }
        val rewritten = JsonObject(root + ("components" to JsonArray(transform(components))))
        return rewritten.toString().toByteArray(Charsets.UTF_8)
    }

    private fun kindOf(component: JsonObject): String = component.getValue("kind").jsonPrimitive.content

    private fun parse(bytes: ByteArray): Outcome<RegionManifest> =
        RegionManifestParser.parse(bytes, "fixture-mini", FixturePackages.FIXTURE_VERSION)

    private fun success(outcome: Outcome<RegionManifest>): RegionManifest =
        when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("parse failed: ${outcome.error}")
        }
}
