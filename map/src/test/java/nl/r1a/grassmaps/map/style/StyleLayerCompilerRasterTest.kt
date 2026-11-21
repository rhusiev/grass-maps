package nl.r1a.grassmaps.map.style

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmaps.plugin.api.model.LayerId
import nl.r1a.grassmaps.plugin.api.model.LayerSpec
import nl.r1a.grassmaps.plugin.api.model.LayerType
import nl.r1a.grassmaps.plugin.api.model.RasterSourceSpec
import nl.r1a.grassmaps.plugin.api.model.SourceId
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
    fun anOfflineAreaCompilesFromItsPackFile() {
        val compiled = StyleLayerCompiler.compile(layer(raster = pack("/data/raster.pmtiles")))

        assertEquals("/data/raster.pmtiles", compiled.raster?.packFile)
    }

    @Test
    fun aPackFileMustBeAnAbsolutePathAndExcludeTileUrls() {
        assertThrows(StyleCompileException::class.java) {
            compileRaster(pack("raster.pmtiles"))
        }
        assertThrows(StyleCompileException::class.java) {
            compileRaster(pack("/data/raster.pmtiles").copy(tiles = listOf(template)))
        }
        assertThrows(StyleCompileException::class.java) {
            compileRaster(pack(null))
        }
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

    /**
     * The compiler keeps the value it is given: the property is the expression
     * for 0.4, not a dropped or coerced one. The plugin's own test reads the
     * exact `raster-opacity` value back at the JSON seam, because the library's
     * literal types are internal to it.
     */
    @Test
    fun aRasterLayerCarriesItsOpacityAsRasterOpacity() {
        val compiled =
            StyleLayerCompiler.compile(
                layer(raster = source(), paint = JsonObject(mapOf("raster-opacity" to JsonPrimitive(0.4)))),
            )

        val opacity = compiled.expression("raster-opacity") ?: throw AssertionError("no raster-opacity")
        assertEquals(StyleExpression.value(JsonPrimitive(0.4)), opacity)
    }

    @Test
    fun anotherLayerTypeCannotCarryRasterOpacity() {
        assertThrows(StyleCompileException::class.java) {
            StyleLayerCompiler.compile(
                layer(raster = source(), type = LayerType.Fill).copy(
                    paint = JsonObject(mapOf("raster-opacity" to JsonPrimitive(0.4))),
                ),
            )
        }
    }

    private fun layer(
        raster: RasterSourceSpec?,
        type: LayerType = LayerType.Raster,
        paint: JsonObject = JsonObject(emptyMap()),
    ): LayerSpec =
        LayerSpec(
            id = LayerId("test.raster"),
            type = type,
            raster = raster,
            paint = paint,
        )

    private fun compileRaster(raster: RasterSourceSpec) = StyleLayerCompiler.compile(layer(raster = raster))

    private fun pack(file: String?): RasterSourceSpec = RasterSourceSpec(packFile = file, maxZoom = 14)

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
