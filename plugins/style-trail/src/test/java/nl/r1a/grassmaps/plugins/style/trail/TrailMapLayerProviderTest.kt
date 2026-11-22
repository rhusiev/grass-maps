package nl.r1a.grassmaps.plugins.style.trail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.r1a.grassmaps.plugin.api.model.LayerSlot
import nl.r1a.grassmaps.plugin.api.model.LayerType
import nl.r1a.grassmaps.plugin.api.model.MapCredits
import nl.r1a.grassmaps.plugin.api.model.MapSourceIds
import nl.r1a.grassmaps.plugin.api.model.PaletteStyleDocument
import nl.r1a.grassmaps.plugin.api.model.SlottedLayer
import nl.r1a.grassmaps.plugin.api.model.StyleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TrailMapLayerProviderTest {
    private val provider = TrailMapLayerProvider()
    private val light = StyleId("trail")
    private val dark = StyleId("trail-dark")
    private val meadowFile = File("../style-meadow/src/main/styles/meadow.json")
    private val meadow = PaletteStyleDocument.parse(meadowFile.readText())

    @Test
    fun vectorSourcesDeclareTheirCredits() {
        for (style in provider.styles()) {
            val credits = style.sources.associate { it.id to it.attribution }

            assertEquals(MapCredits.OPENSTREETMAP, credits[MapSourceIds.WORLD])
            assertEquals(MapCredits.OPENSTREETMAP, credits[MapSourceIds.REGION])
            assertEquals(MapCredits.COPERNICUS_DEM, credits[MapSourceIds.CONTOURS])
        }
    }

    @Test
    fun lightAndDarkShareOneBaseDocument() {
        val styles = provider.styles()

        assertEquals(listOf(light, dark), styles.map { it.id })
        assertEquals(styles[0].baseStyleJson, styles[1].baseStyleJson)
        assertEquals(PaletteStyleDocument.sources, styles[0].sources)
    }

    @Test
    fun bothVariantsDeclareTheWorldAndTheRegionSource() {
        for (style in provider.styles()) {
            assertEquals(
                listOf(MapSourceIds.WORLD, MapSourceIds.REGION),
                style.sources.map { it.id }.filter { it == MapSourceIds.WORLD || it == MapSourceIds.REGION },
            )
        }
    }

    @Test
    fun baseDocumentEqualsMeadowSoSwitchingNeedsNoReload() {
        assertEquals(meadow.baseStyle(), provider.styles().first().baseStyleJson)
    }

    @Test
    fun layersAreSplitAcrossWorldAndRegion() {
        val base =
            provider
                .layers(light)
                .filter { it.slot == LayerSlot.Base && it.spec.type != LayerType.Background }
        val world = base.filter { it.spec.source == MapSourceIds.WORLD }
        val region = base.filter { it.spec.source == MapSourceIds.REGION }

        assertTrue(world.isNotEmpty())
        assertTrue(region.isNotEmpty())
        assertTrue(world.all { it.spec.minZoom < 8f && it.spec.maxZoom <= 8f })
        assertTrue(region.all { it.spec.minZoom >= 8f })
    }

    @Test
    fun theBaseStyleAddsNoOverlayLayers() {
        for (styleId in listOf(light, dark)) {
            val overlays = provider.layers(styleId).filter { it.slot == LayerSlot.Overlays }

            assertTrue("$styleId: contours are the contour plugin's layers", overlays.isEmpty())
        }
    }

    @Test
    fun trailStartsEachSharedLayerOneZoomEarlierThanMeadow() {
        val meadowRanges = rangesById(meadow.layersFor(StyleId("meadow")))
        val trailRanges = rangesById(provider.layers(light))
        // The dash ladder redraws the path layers with its own ranges.
        val shared = meadowRanges.keys.intersect(trailRanges.keys) - PATH_LADDER.toSet()

        assertTrue(shared.size > MIN_SHARED_LAYERS)
        assertTrue(shared.any { meadowRanges.getValue(it).second < MAX_AUTHORED_ZOOM })
        for (id in shared) {
            val (meadowMin, meadowMax) = meadowRanges.getValue(id)
            val expectedMax = if (meadowMax >= MAX_AUTHORED_ZOOM) meadowMax else (meadowMax - 1f).coerceAtLeast(0f)
            assertEquals("$id min", (meadowMin - 1f).coerceAtLeast(0f), trailRanges.getValue(id).first)
            assertEquals("$id max", expectedMax, trailRanges.getValue(id).second)
        }
    }

    @Test
    fun lightAndDarkShareLayerIdsButSwapPaletteColours() {
        val lightLayers = provider.layers(light)
        val darkLayers = provider.layers(dark)

        assertEquals(lightLayers.map { it.spec.id }, darkLayers.map { it.spec.id })
        assertNotEquals(lightLayers.map { it.spec.paint }, darkLayers.map { it.spec.paint })
    }

    @Test
    fun everyColourTokenIsResolved() {
        val paints =
            listOf(light, dark).flatMap { styleId ->
                provider.layers(styleId).map { it.spec.paint.toString() }
            }

        assertTrue(paints.none { it.contains("\"@") })
    }

    @Test
    fun pathKindsFormADashLadderFromTwelve() {
        val specs =
            PATH_LADDER.map { id ->
                provider.layers(light).single { it.spec.id.value == id + "__region" }.spec
            }

        assertTrue(specs.all { it.minZoom == 12f })
        val patterns = specs.map { it.paint["line-dasharray"].toString() }
        assertEquals(patterns.take(4).distinct().size, 4)
    }

    @Test
    fun symbolLayersMatchMeadowSoTheSpriteStillCoversThem() {
        val meadowSymbols = authoredSymbols(meadowFile)
        val trailSymbols = authoredSymbols(File("src/main/styles/trail.json"))

        assertEquals(meadowSymbols, trailSymbols)
    }

    private fun rangesById(layers: List<SlottedLayer>): Map<String, Pair<Float, Float>> =
        layers
            .groupBy {
                it.spec.id.value
                    .removeSuffix("__world")
                    .removeSuffix("__region")
            }.mapValues { (_, pieces) -> pieces.minOf { it.spec.minZoom } to pieces.maxOf { it.spec.maxZoom } }

    private fun authoredSymbols(file: File): List<JsonObject> =
        Json
            .parseToJsonElement(file.readText())
            .jsonObject
            .getValue("layers")
            .jsonArray
            .map { it.jsonObject }
            .filter { it.getValue("type").jsonPrimitive.content == "symbol" }

    private companion object {
        const val MAX_AUTHORED_ZOOM = 24f
        const val MIN_SHARED_LAYERS = 50
        val PATH_LADDER = listOf("roads_footway", "roads_path", "roads_steps", "roads_track", "roads_cycleway")
    }
}
