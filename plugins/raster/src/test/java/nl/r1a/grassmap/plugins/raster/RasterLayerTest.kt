package nl.r1a.grassmap.plugins.raster

import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerType
import nl.r1a.grassmap.plugin.api.model.SlottedLayer
import nl.r1a.grassmap.plugin.api.model.StyleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `raster-opacity` value a layer carries, exactly as the style writes it. */
private fun rasterOpacity(layer: SlottedLayer): String =
    (layer.spec.paint[RasterTransparency.RASTER_OPACITY_PROPERTY] as? JsonPrimitive)?.content
        ?: throw AssertionError("no raster-opacity on ${layer.spec.id}")

/** The raster layer, its placement and transparency, and the provider that shows it. */
class RasterLayerTest {
    @Test
    fun theOsmLayerCarriesItsTileTemplateAndAttribution() {
        val layer = RasterLayers.layer(RasterPresets.openStreetMap)

        assertEquals(RasterLayers.LAYER_ID, layer.spec.id)
        assertEquals(LayerType.Raster, layer.spec.type)
        val raster = layer.spec.raster ?: throw AssertionError("no raster source")
        assertEquals(
            listOf("https://tile.openstreetmap.org/{z}/{x}/{y}.png"),
            raster.tiles,
        )
        assertEquals(0, raster.minZoom)
        assertEquals(19, raster.maxZoom)
        assertEquals(256, raster.tileSize)
        assertTrue(raster.attributionHtml.orEmpty(), raster.attributionHtml.orEmpty().contains("OpenStreetMap"))
    }

    @Test
    fun anOverlayDrawsAboveTheBaseMapAndAnUnderlayBelowIt() {
        val overlay = RasterLayers.layer(RasterPresets.openStreetMap, RasterPlacement.Overlay)
        val underlay = RasterLayers.layer(RasterPresets.openStreetMap, RasterPlacement.Underlay)

        assertEquals(LayerSlot.Terrain, overlay.slot)
        assertEquals(LayerSlot.Underlay, underlay.slot)
    }

    @Test
    fun fortyPercentIsTheRasterOpacityStyleProperty() {
        val layer =
            RasterLayers.layer(
                source = RasterPresets.openStreetMap,
                placement = RasterPlacement.Overlay,
                opacity = RasterTransparency.opacityOf(40),
            )

        assertEquals("0.4", rasterOpacity(layer))
    }

    @Test
    fun anOpacityOutsideTheRangeIsClampedToTheStyleProperty() {
        val above = RasterLayers.layer(RasterPresets.openStreetMap, opacity = 1.5f)
        val below = RasterLayers.layer(RasterPresets.openStreetMap, opacity = -0.2f)

        assertEquals("1.0", rasterOpacity(above))
        assertEquals("0.0", rasterOpacity(below))
    }

    @Test
    fun anEmptyAttributionIsNotAnEmptyCredit() {
        val source =
            when (val outcome = RasterSources.custom("https://t.example/{z}/{x}/{y}.png")) {
                is Outcome.Success -> outcome.value
                is Outcome.Failure -> throw AssertionError()
            }

        assertNull(
            RasterLayers
                .layer(source)
                .spec.raster
                ?.attributionHtml,
        )
    }

    @Test
    fun theLayerStaysVisibleAboveTheSourcesTopTileZoom() {
        // A source that serves only 12 and 13 must still draw at 13.0: MapLibre
        // hides a layer at or above its maxzoom, so a layer that copied the
        // source's range would vanish at the fixture's own capture zoom.
        val narrow =
            when (
                val outcome =
                    RasterSources.custom(
                        "https://t.example/{z}/{x}/{y}.png",
                        minZoom = 12,
                        maxZoom = 13,
                    )
            ) {
                is Outcome.Success -> outcome.value
                is Outcome.Failure -> throw AssertionError()
            }

        val layer = RasterLayers.layer(narrow)

        assertEquals(13, layer.spec.raster?.maxZoom)
        assertTrue(
            "layer maxzoom ${layer.spec.maxZoom} must stay above the source's 13",
            layer.spec.maxZoom > 13f,
        )
        assertTrue("layer minzoom ${layer.spec.minZoom}", layer.spec.minZoom <= 12f)
    }

    @Test
    fun theProviderDrawsOnlyTheShownSource() {
        val provider = RasterLayerProvider()
        val style = StyleId("standard")

        assertTrue(provider.layers(style).isEmpty())

        provider.show(RasterPresets.openStreetMap)
        assertEquals(listOf(RasterLayers.layer(RasterPresets.openStreetMap)), provider.layers(style))
        assertTrue(provider.allows("https://tile.openstreetmap.org/5/10/20.png"))
        assertFalse(provider.allows("https://example.org/5/10/20.png"))

        provider.show(null)
        assertTrue(provider.layers(style).isEmpty())
        assertFalse(provider.allows("https://tile.openstreetmap.org/5/10/20.png"))
    }

    @Test
    fun theProviderCarriesThePlacementAndTransparencyOntoTheLayer() {
        val provider = RasterLayerProvider()
        provider.show(
            source = RasterPresets.openStreetMap,
            placement = RasterPlacement.Underlay,
            opacity = RasterTransparency.opacityOf(40),
        )

        val layer = provider.layers(StyleId("standard")).single()

        assertEquals(LayerSlot.Underlay, layer.slot)
        assertEquals("0.4", rasterOpacity(layer))
        // The gate answers for the shown source whatever its slot: an underlay
        // is still an online source, not an offline one.
        assertTrue(provider.allows("https://tile.openstreetmap.org/5/10/20.png"))
    }
}
