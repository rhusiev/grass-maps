package nl.r1a.grassmap.map.style

import nl.r1a.grassmap.plugin.api.model.LayerId
import nl.r1a.grassmap.plugin.api.model.LayerSpec
import nl.r1a.grassmap.plugin.api.model.LayerType
import nl.r1a.grassmap.plugin.api.model.RasterSourceSpec
import nl.r1a.grassmap.plugin.api.model.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The layer seam accepts a raster tile source and rejects a malformed one. */
class StyleLayerCompilerRasterTest {
    private val template = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"

    @Test
    fun aRasterLayerCompilesWithItsOwnTileSource() {
        val compiled = StyleLayerCompiler.compile(layer(raster = source()))

        assertEquals(LayerType.Raster, compiled.type)
        assertEquals(template, compiled.raster?.tiles?.single())
        assertEquals(19, compiled.raster?.maxZoom)
    }

    @Test
    fun aRasterLayerWithoutASourceIsRejected() {
        assertThrows(StyleCompileException::class.java) {
            StyleLayerCompiler.compile(layer(raster = null))
        }
    }

    @Test
    fun anotherLayerTypeCannotAttachARasterSource() {
        assertThrows(StyleCompileException::class.java) {
            StyleLayerCompiler.compile(layer(raster = source(), type = LayerType.Fill))
        }
    }

    @Test
    fun aRasterLayerCannotAlsoNameAVectorSource() {
        assertThrows(StyleCompileException::class.java) {
            StyleLayerCompiler.compile(
                layer(raster = source()).copy(source = SourceId("region")),
            )
        }
    }

    @Test
    fun aTemplateWithoutThePlaceholdersIsRejected() {
        assertThrows(StyleCompileException::class.java) {
            StyleLayerCompiler.compile(layer(raster = source(tiles = listOf("https://t.example/{z}/{x}.png"))))
        }
    }

    @Test
    fun anOutOfRangeZoomOrTileSizeIsRejected() {
        assertThrows(StyleCompileException::class.java) {
            StyleLayerCompiler.compile(layer(raster = source(maxZoom = 25)))
        }
        assertThrows(StyleCompileException::class.java) {
            StyleLayerCompiler.compile(layer(raster = source(tileSize = 0)))
        }
    }

    private fun layer(
        raster: RasterSourceSpec?,
        type: LayerType = LayerType.Raster,
    ): LayerSpec =
        LayerSpec(
            id = LayerId("test.raster"),
            type = type,
            raster = raster,
        )

    private fun source(
        tiles: List<String> = listOf(template),
        tileSize: Int = 256,
        maxZoom: Int = 19,
    ): RasterSourceSpec =
        RasterSourceSpec(
            tiles = tiles,
            tileSize = tileSize,
            minZoom = 0,
            maxZoom = maxZoom,
            attributionHtml = "OpenStreetMap",
        )
}
