package nl.r1a.grassmaps.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.r1a.grassmaps.plugin.api.model.MapSource
import nl.r1a.grassmaps.plugin.api.model.MapSourceIds
import nl.r1a.grassmaps.plugin.api.model.SourceId
import nl.r1a.grassmaps.plugin.api.model.StyleId
import nl.r1a.grassmaps.plugin.api.model.StyleSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that keeps one logical source per kind: a source the app supplies
 * tiles for is never also written into the base style as a URI, so a shared
 * tile is loaded once.
 */
class SourcePlanTest {
    @Test
    fun aSourceWithAppTilesIsNotWrittenAsAUri() {
        val plan =
            planSources(
                style(),
                resolver = { "pmtiles://file:///region.pmtiles" },
                appTiles = mapOf(MapSourceIds.REGION to tiles),
            )

        assertTrue("a supplied source must not also have a URI", MapSourceIds.REGION !in plan.uris)
        assertEquals(listOf(MapSourceIds.REGION), plan.app.map { it.id })
    }

    @Test
    fun anAppSourceKeepsTheStylesZoomRange() {
        val source = MapSource(MapSourceIds.REGION, minZoom = 8, maxZoom = 14)

        val plan =
            planSources(
                style(sources = listOf(source)),
                resolver = { null },
                appTiles = mapOf(MapSourceIds.REGION to tiles),
            )

        val supplied = plan.app.single()
        assertEquals(8, supplied.minZoom)
        assertEquals(14, supplied.maxZoom)
    }

    @Test
    fun aSourceWithoutAppTilesKeepsItsUri() {
        val plan = planSources(style(), resolver = { id -> "pmtiles://file:///$id.pmtiles" }, appTiles = emptyMap())

        assertEquals("pmtiles://file:///world.pmtiles", plan.uris[MapSourceIds.WORLD])
        assertEquals("pmtiles://file:///region.pmtiles", plan.uris[MapSourceIds.REGION])
        assertTrue(plan.app.isEmpty())
    }

    @Test
    fun aDeclaredSourceWithNeitherIsLeftOut() {
        val plan = planSources(style(), resolver = { null }, appTiles = emptyMap())

        assertTrue(plan.uris.isEmpty())
        assertTrue(plan.app.isEmpty())
    }

    @Test
    fun bothWorldAndRegionCanReadTheSameAppTiles() {
        val plan =
            planSources(
                style(),
                resolver = { null },
                appTiles = mapOf(MapSourceIds.WORLD to tiles, MapSourceIds.REGION to tiles),
            )

        assertEquals(listOf(MapSourceIds.WORLD, MapSourceIds.REGION), plan.app.map { it.id })
        assertEquals(
            "both ids share one source",
            1,
            plan.app
                .map { it.tiles }
                .toSet()
                .size,
        )
    }

    @Test
    fun aSourceTheStyleDoesNotDeclareIsNotPlanned() {
        val plan = planSources(style(), resolver = { null }, appTiles = mapOf(SourceId("raster") to tiles))

        assertTrue(plan.app.isEmpty())
        assertTrue(plan.uris.isEmpty())
    }

    @Test
    fun creditsCoverOnlySourcesThisBuildHasDataFor() {
        val plan =
            planSources(
                style(sources = credited()),
                resolver = { id -> "pmtiles://$id".takeIf { id == MapSourceIds.WORLD } },
                appTiles = emptyMap(),
            )

        assertEquals(listOf(WORLD_CREDIT), plan.credits)
    }

    @Test
    fun anAppSuppliedSourceStillContributesItsCredit() {
        val plan =
            planSources(
                style(sources = credited()),
                resolver = { null },
                appTiles = mapOf(MapSourceIds.REGION to tiles),
            )

        assertEquals(listOf(REGION_CREDIT), plan.credits)
    }

    @Test
    fun aBuiltStyleWritesTheCreditIntoEachUriSource() {
        val built =
            requireNotNull(
                buildBaseStyle(style(sources = credited()), { "pmtiles://$it" }, emptyMap()),
            )

        val sources =
            Json
                .parseToJsonElement(built.json.json)
                .jsonObject
                .getValue("sources")
                .jsonObject
        assertEquals(
            WORLD_CREDIT,
            sources
                .getValue("world")
                .jsonObject
                .getValue("attribution")
                .jsonPrimitive.content,
        )
        assertEquals(
            REGION_CREDIT,
            sources
                .getValue("region")
                .jsonObject
                .getValue("attribution")
                .jsonPrimitive.content,
        )
    }

    private fun credited(): List<MapSource> =
        listOf(
            MapSource(MapSourceIds.WORLD, minZoom = 0, maxZoom = 7, attribution = WORLD_CREDIT),
            MapSource(MapSourceIds.REGION, minZoom = 8, maxZoom = 14, attribution = REGION_CREDIT),
        )

    private fun style(
        sources: List<MapSource> =
            listOf(
                MapSource(MapSourceIds.WORLD, minZoom = 0, maxZoom = 7),
                MapSource(MapSourceIds.REGION, minZoom = 8, maxZoom = 14),
            ),
    ): StyleSpec =
        StyleSpec(
            id = StyleId("standard"),
            baseStyleJson = BASE_STYLE,
            sources = sources,
        )

    private val tiles = MapTileSource { _, _, _ -> EMPTY_TILE }

    private companion object {
        const val WORLD_CREDIT = "world credit"
        const val REGION_CREDIT = "region credit"
        const val BASE_STYLE = """{"version":8,"glyphs":"","layers":[]}"""
        val EMPTY_TILE = ByteArray(0)
    }
}
