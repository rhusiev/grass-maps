package nl.r1a.grassmap.plugins.raster

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerType
import nl.r1a.grassmap.plugin.api.model.StyleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The raster layer and the provider that shows or removes it. */
class RasterLayerTest {
    @Test
    fun theOsmLayerCarriesItsTileTemplateAndAttribution() {
        val layer = RasterLayers.layer(RasterPresets.openStreetMap)

        assertEquals(LayerSlot.Base, layer.slot)
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
}
