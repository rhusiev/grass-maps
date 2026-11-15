package nl.r1a.grassmap.regions

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.CatalogPartKind
import nl.r1a.grassmap.plugin.api.model.CatalogRegionKind
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RegionCatalog
import nl.r1a.grassmap.plugin.api.testing.fixture.CatalogFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue reader against the real T-0501 output (T-0504).
 *
 * The fixture is the file the pipeline wrote, so these tests pin the shape the
 * server actually serves. The variants add only entries and fields the protocol
 * declares, and are derived from that same file.
 */
class RegionCatalogParserTest {
    @Test
    fun theRealBuildCatalogueParses() {
        val catalog = parse(CatalogFixture.bytes())

        assertEquals(RegionCatalogParser.SUPPORTED_FORMAT, catalog.format)
        assertEquals(CatalogFixture.REGION_COUNT, catalog.regions.size)
        assertEquals(2, catalog.attribution.size)
        assertTrue(catalog.generated != null)
        assertEquals(null, catalog.world)
    }

    @Test
    fun everyOblastCarriesItsFivePartsAndItsDataDate() {
        val catalog = parse(CatalogFixture.bytes())

        for (region in catalog.regions) {
            assertEquals("region ${region.id}", CatalogRegionKind.REGION, region.kind)
            assertEquals("region ${region.id}", CatalogFixture.VERSION, region.version)
            assertEquals("region ${region.id}", "2026-11-03", region.dataDate)
            assertEquals(
                "region ${region.id}",
                listOf(
                    CatalogPartKind.MAP,
                    CatalogPartKind.CONTOURS,
                    CatalogPartKind.SEARCH,
                    CatalogPartKind.ELEVATION,
                    CatalogPartKind.ROUTING,
                ).sorted(),
                region.parts.map { it.kind }.sorted(),
            )
            assertTrue("region ${region.id} has no name", region.nameEn != null && region.nameUk != null)
            assertNotNull("region ${region.id} has no bbox", region.bounds)
            assertTrue("region ${region.id} has no size", region.bytes > 0)
            assertTrue("region ${region.id} has a part without a size", region.parts.all { it.bytes > 0 })
        }
    }

    @Test
    fun lvivIsReadWithItsUkrainianNameAndItsParts() {
        val catalog = parse(CatalogFixture.bytes())
        val lviv = catalog.regions.single { it.id == CatalogFixture.LVIV }

        assertEquals("Lviv Oblast", lviv.nameEn)
        assertEquals("Львівська область", lviv.nameUk)
        assertEquals(CatalogFixture.VERSION, lviv.version)
        assertEquals(210_943_205L, lviv.bytes)
        assertEquals(65_699_471L, lviv.parts.single { it.kind == CatalogPartKind.MAP }.bytes)
        assertEquals(97_198_080L, lviv.parts.single { it.kind == CatalogPartKind.ROUTING }.bytes)
        assertEquals("Lviv Oblast", lviv.name(preferUkrainian = false))
        assertEquals("Львівська область", lviv.name(preferUkrainian = true))
        assertTrue(lviv.bounds!!.contains(GeoPoint(49.84, 24.03)))
    }

    @Test
    fun aWorldEntryIsFoundByKindAndKeptOutOfTheRegionList() {
        val catalog = parse(CatalogFixture.withWorld())

        assertEquals(CatalogFixture.WORLD_ID, catalog.world?.id)
        assertEquals(CatalogRegionKind.WORLD, catalog.world?.kind)
        assertEquals(CatalogFixture.REGION_COUNT, catalog.entries().size)
        assertFalse(catalog.entries().any { it.isWorld })
    }

    @Test
    fun aThirtyMetreAddonIsReadAsAPartWithItsSize() {
        val catalog = parse(CatalogFixture.withElevationAddon())

        val addon = catalog.regions.single { it.id == CatalogFixture.LVIV }.addons.single()
        assertEquals(CatalogPartKind.ELEVATION_30M, addon.kind)
        assertEquals("2026-01", addon.version)
        assertEquals(13_000_000L, addon.bytes)
    }

    @Test
    fun anUnknownCatalogueFormatIsUnsupported() {
        val bytes = CatalogFixture.withCatalog { it.withFormat(2) }

        assertEquals(Outcome.Failure(AppError.Unsupported), RegionCatalogParser.parse(bytes))
    }

    @Test
    fun anUnknownPartKindIsKeptWithItsWireName() {
        val bytes =
            CatalogFixture.withRegion(CatalogFixture.VINNYTSIA) { entry ->
                entry.withParts { parts ->
                    parts.map { part ->
                        if (part.jsonPrimitive("kind") == "contours") part.withKind("house-numbers") else part
                    }
                }
            }

        val unknown = parse(bytes).regions.first().parts.single { it.wireKind == "house-numbers" }
        assertEquals(CatalogPartKind.OTHER, unknown.kind)
    }

    @Test
    fun anUnknownRegionKindIsNotTreatedAsAnOblast() {
        val bytes = CatalogFixture.withRegion(CatalogFixture.VINNYTSIA) { it.withField("kind", JsonPrimitive("continent")) }

        val catalog = parse(bytes)
        assertEquals(CatalogRegionKind.OTHER, catalog.regions.first().kind)
        assertEquals(CatalogFixture.REGION_COUNT, catalog.entries().size)
    }

    @Test
    fun aRegionWithoutABboxIsCorrupt() {
        val bytes = CatalogFixture.withRegion(CatalogFixture.VINNYTSIA) { it.without("bbox") }

        assertEquals(Outcome.Failure(AppError.CorruptData), RegionCatalogParser.parse(bytes))
    }

    @Test
    fun aRepeatedPartKindIsCorrupt() {
        val bytes =
            CatalogFixture.withRegion(CatalogFixture.VINNYTSIA) { entry ->
                entry.withParts { parts -> parts + listOf(parts.first()) }
            }

        assertEquals(Outcome.Failure(AppError.CorruptData), RegionCatalogParser.parse(bytes))
    }

    @Test
    fun aRepeatedRegionIsCorrupt() {
        val bytes =
            CatalogFixture.withCatalog { root ->
                val regions = root.getArray("regions")
                root.withArray("regions", regions + listOf(regions.first()))
            }

        assertEquals(Outcome.Failure(AppError.CorruptData), RegionCatalogParser.parse(bytes))
    }

    @Test
    fun aRegionWithNoNameInEitherLanguageIsCorrupt() {
        val bytes = CatalogFixture.withRegion(CatalogFixture.VINNYTSIA) { it.without("name") }

        assertEquals(Outcome.Failure(AppError.CorruptData), RegionCatalogParser.parse(bytes))
    }

    @Test
    fun oneNameInEitherLanguageIsEnough() {
        val bytes =
            CatalogFixture.withRegion(CatalogFixture.VINNYTSIA) { entry ->
                val name = (entry.getValue("name") as JsonObject)
                entry.withField("name", JsonObject(name.filterKeys { it == "en" }))
            }

        val region = parse(bytes).regions.first()
        assertEquals("Vinnytsia Oblast", region.nameEn)
        assertEquals(null, region.nameUk)
        assertEquals("Vinnytsia Oblast", region.name(preferUkrainian = true))
    }

    @Test
    fun anOutlineThatEscapesTheServerIsCorrupt() {
        val bytes = CatalogFixture.withRegion(CatalogFixture.VINNYTSIA) { it.withField("outline", JsonPrimitive("../ua-05.geojson")) }

        assertEquals(Outcome.Failure(AppError.CorruptData), RegionCatalogParser.parse(bytes))
    }

    @Test
    fun aPartWithoutASizeIsCorrupt() {
        val bytes =
            CatalogFixture.withRegion(CatalogFixture.VINNYTSIA) { entry ->
                entry.withParts { parts -> listOf(parts.first().without("size_bytes")) + parts.drop(1) }
            }

        assertEquals(Outcome.Failure(AppError.CorruptData), RegionCatalogParser.parse(bytes))
    }

    @Test
    fun aVersionWithoutADateHasNoDataDate() {
        assertEquals("2026-11-03", RegionCatalogParser.dataDateOf("2026-11-03"))
        assertEquals("2026-11-03", RegionCatalogParser.dataDateOf("2026-11-03-1"))
        assertEquals(null, RegionCatalogParser.dataDateOf("staging"))
        assertEquals(null, RegionCatalogParser.dataDateOf("2026-11"))
    }

    private fun parse(bytes: ByteArray): RegionCatalog =
        when (val outcome = RegionCatalogParser.parse(bytes)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("catalogue refused: ${outcome.error}")
        }

    private fun JsonObject.withFormat(value: Int): JsonObject = withField("format", JsonPrimitive(value))

    private fun JsonObject.withField(
        key: String,
        value: JsonElement,
    ): JsonObject = JsonObject(this + (key to value))

    private fun JsonObject.without(key: String): JsonObject = JsonObject(filterKeys { it != key })

    private fun JsonObject.withArray(
        key: String,
        value: List<JsonElement>,
    ): JsonObject = withField(key, JsonArray(value))

    private fun JsonObject.getArray(key: String): List<JsonElement> =
        getValue(key) as JsonArray

    private fun JsonObject.withParts(transform: (List<JsonObject>) -> List<JsonObject>): JsonObject {
        val parts = (getValue("parts") as JsonArray).map { it as JsonObject }
        return withArray("parts", transform(parts))
    }

    private fun JsonElement.jsonPrimitive(key: String): String = (this as JsonObject).getValue(key).jsonPrimitive.content

    private fun JsonElement.withKind(kind: String): JsonObject = (this as JsonObject).withField("kind", JsonPrimitive(kind))
}
