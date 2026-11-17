package nl.r1a.grassmap.plugins.raster

import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The size estimate, the hard cap and the licence the area presets carry (T-0513). */
class RasterAreaPlanTest {
    @Test
    fun theWholeWorldAtZoomOneIsFourTiles() {
        val range = RasterTileRange.of(WORLD, zoom = 1)

        assertEquals(4L, range.count)
        assertEquals(0L, range.minX)
        assertEquals(1L, range.maxX)
    }

    @Test
    fun lvivAtZoomEightSpansTwoRows() {
        val range = RasterTileRange.of(LVIV, zoom = 8)

        assertEquals(2L, range.count)
        assertEquals(145L, range.minX)
        assertEquals(86L, range.minY)
        assertEquals(87L, range.maxY)
    }

    @Test
    fun theEstimateIsTheTileCountTimesThePresetTileSize() {
        val plan = RasterAreaPlan.of(RasterPresets.eox2018, LVIV, minZoom = 6, maxZoom = 9)

        assertEquals(plan.tileCount * RasterPresets.eox2018.estimatedTileBytes, plan.estimatedBytes)
        assertNull(plan.refusal)
    }

    @Test
    fun theZoomRangeIsClampedToTheSource() {
        val plan = RasterAreaPlan.of(RasterPresets.eox2018, LVIV, minZoom = -3, maxZoom = 40)

        assertEquals(RasterPresets.eox2018.minZoom, plan.minZoom)
        assertEquals(RasterPresets.eox2018.maxZoom, plan.maxZoom)
    }

    @Test
    fun anAreaPastTheTileCapIsRefused() {
        val plan = RasterAreaPlan.of(RasterPresets.eox2018, WORLD, minZoom = 0, maxZoom = 9)

        assertTrue(plan.tileCount > RasterAreaPlan.MAX_TILES)
        assertEquals(RasterAreaPlan.Refusal.TooManyTiles, plan.refusal)
    }

    @Test
    fun anAreaPastTheByteCapIsRefusedWhenTilesAreLarge() {
        val heavy = RasterPresets.eox2018.copy(estimatedTileBytes = RasterAreaPlan.MAX_BYTES)
        val plan = RasterAreaPlan.of(heavy, LVIV, minZoom = 6, maxZoom = 8)

        assertEquals(RasterAreaPlan.Refusal.TooManyBytes, plan.refusal)
    }

    @Test
    fun theOpenStreetMapPresetNeverPlansADownload() {
        val plan = RasterAreaPlan.of(RasterPresets.openStreetMap, LVIV, minZoom = 6, maxZoom = 7)

        assertEquals(RasterAreaPlan.Refusal.SourceForbidsOffline, plan.refusal)
        assertFalse(RasterPresets.areaSources.any { it.isOpenStreetMap() })
    }

    @Test
    fun freeSpaceBelowTwiceTheEstimateRefusesTheArea() {
        val plan = RasterAreaPlan.of(RasterPresets.eox2018, LVIV, minZoom = 6, maxZoom = 8)

        assertEquals(RasterAreaPlan.Refusal.NotEnoughSpace, plan.refusalFor(plan.estimatedBytes))
        assertNull(plan.refusalFor(plan.estimatedBytes * RasterAreaPlan.SPACE_FACTOR + 1))
    }

    @Test
    fun theTwentyEighteenLayerCarriesItsNonCommercialLicence() {
        val licence = checkNotNull(RasterPresets.eox2018.licence)

        assertTrue(licence.isNonCommercial)
        assertTrue(licence.name.contains("CC BY-NC-SA 4.0"))
        assertTrue(licence.url.startsWith("https://"))
        assertFalse(checkNotNull(RasterPresets.eox2016.licence).isNonCommercial)
    }

    private companion object {
        val WORLD = BoundingBox(GeoPoint(-80.0, -179.0), GeoPoint(80.0, 179.0))
        val LVIV = BoundingBox(GeoPoint(49.83, 24.00), GeoPoint(49.85, 24.05))
    }
}
