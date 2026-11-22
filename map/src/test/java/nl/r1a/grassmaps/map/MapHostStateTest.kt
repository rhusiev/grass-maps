package nl.r1a.grassmaps.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.r1a.grassmaps.map.style.StyleLayerCompiler
import nl.r1a.grassmaps.plugin.api.contract.PluginId
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.Camera
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.LayerId
import nl.r1a.grassmaps.plugin.api.model.LayerSlot
import nl.r1a.grassmaps.plugin.api.model.LayerSpec
import nl.r1a.grassmaps.plugin.api.model.LayerType
import nl.r1a.grassmaps.plugin.api.model.MapSource
import nl.r1a.grassmaps.plugin.api.model.MapSourceIds
import nl.r1a.grassmaps.plugin.api.model.MapTrack
import nl.r1a.grassmaps.plugin.api.model.SlottedLayer
import nl.r1a.grassmaps.plugin.api.model.SourceId
import nl.r1a.grassmaps.plugin.api.model.StyleDiff
import nl.r1a.grassmaps.plugin.api.model.StyleId
import nl.r1a.grassmaps.plugin.api.model.StyleSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MapHostStateTest {
    @Test
    fun aDiffForTheLoadedStyleIsApplied() {
        val session = session()

        val result =
            session.applyStyleDiff(
                StyleDiff(LOADED_STYLE, listOf(background("applied"))),
            )

        assertEquals(Outcome.Success(Unit), result)
        assertEquals(listOf(LayerId("applied")), session.layers.value.map { it.spec.id })
    }

    @Test
    fun aDiffForAnotherStyleIsRejectedAndLayersStay() {
        val session = session()
        session.applyStyleDiff(StyleDiff(LOADED_STYLE, listOf(background("kept"))))
        val before = session.layers.value

        val result =
            session.applyStyleDiff(
                StyleDiff(StyleId("trail"), listOf(background("stale"))),
            )

        assertEquals(Outcome.Failure(AppError.InvalidInput), result)
        assertEquals(before, session.layers.value)
    }

    @Test
    fun setStyleChangesWhichDiffIsAccepted() {
        val session = session()

        assertEquals(Outcome.Success(Unit), session.applyStyle(style(StyleId("trail"))))
        assertEquals(
            Outcome.Failure(AppError.InvalidInput),
            session.applyStyleDiff(StyleDiff(LOADED_STYLE, listOf(background("stale")))),
        )
        assertEquals(
            Outcome.Success(Unit),
            session.applyStyleDiff(StyleDiff(StyleId("trail"), listOf(background("new")))),
        )
        assertEquals(StyleId("trail"), session.loadedStyleId)
    }

    @Test
    fun anInlineDataLayerIsAcceptedAndStored() {
        val session = session()
        val spec =
            LayerSpec(
                id = LayerId("relation-band"),
                type = LayerType.Line,
                data = """{"type":"FeatureCollection","features":[]}""",
                paint = JsonObject(mapOf("line-color" to JsonPrimitive("#ffffff"))),
            )

        val result = session.addLayer(LayerSlot.Routes, spec, PluginId("route.relations"))

        assertEquals(Outcome.Success(Unit), result)
        assertEquals(
            spec,
            session.layers.value
                .single()
                .spec,
        )
    }

    @Test
    fun aLayerWithBothASourceAndInlineDataIsRejected() {
        val session = session()
        val spec =
            LayerSpec(
                id = LayerId("relation-band"),
                type = LayerType.Line,
                source = MapSourceIds.REGION,
                data = """{"type":"FeatureCollection","features":[]}""",
                paint = JsonObject(mapOf("line-color" to JsonPrimitive("#ffffff"))),
            )

        val result = session.addLayer(LayerSlot.Routes, spec, PluginId("route.relations"))

        assertEquals(Outcome.Failure(AppError.InvalidInput), result)
        assertTrue(session.layers.value.isEmpty())
    }

    @Test
    fun aLayerTypeThatIsNotRenderedYetIsRejected() {
        val session = session()

        val result =
            session.applyStyleDiff(
                StyleDiff(
                    LOADED_STYLE,
                    listOf(
                        SlottedLayer(
                            LayerSlot.Markers,
                            LayerSpec(
                                id = LayerId("marker"),
                                type = LayerType.Raster,
                                source = MapSourceIds.REGION,
                            ),
                        ),
                    ),
                ),
            )

        assertEquals(Outcome.Failure(AppError.Unsupported), result)
        assertTrue(session.layers.value.isEmpty())
    }

    @Test
    fun aCircleLayerCarriesItsPlateAndRing() {
        val session = session()
        val spec =
            LayerSpec(
                id = LayerId("route.relations.node"),
                type = LayerType.Circle,
                data = """{"type":"FeatureCollection","features":[]}""",
                paint =
                    JsonObject(
                        mapOf(
                            "circle-color" to JsonPrimitive("#ffffff"),
                            "circle-radius" to JsonPrimitive(5.5),
                            "circle-stroke-color" to JsonPrimitive("#2e9e44"),
                            "circle-stroke-width" to JsonPrimitive(2.2),
                        ),
                    ),
            )

        assertEquals(Outcome.Success(Unit), session.addLayer(LayerSlot.Routes, spec, PluginId("route.relations")))
        assertEquals(
            spec,
            session.layers.value
                .single()
                .spec,
        )
    }

    @Test
    fun anUnknownPropertyIsRejected() {
        val session = session()

        val result =
            session.applyStyleDiff(
                StyleDiff(
                    LOADED_STYLE,
                    listOf(
                        SlottedLayer(
                            LayerSlot.Base,
                            LayerSpec(
                                id = LayerId("line"),
                                type = LayerType.Line,
                                source = MapSourceIds.REGION,
                                paint = JsonObject(mapOf("line-unknown" to JsonPrimitive(1))),
                            ),
                        ),
                    ),
                ),
            )

        assertEquals(Outcome.Failure(AppError.Unsupported), result)
        assertTrue(session.layers.value.isEmpty())
    }

    @Test
    fun switchingToAStyleWithTheSameBaseDocumentKeepsTheLoadedStyle() {
        val session = session()
        session.onStyleLoaded()
        val generation = session.styleGeneration.value

        assertEquals(Outcome.Success(Unit), session.applyStyle(style(StyleId("trail"))))

        assertEquals(LOADED_STYLE, session.loadedStyleId)
        assertTrue("a layers-only switch must not clear styleReady", session.styleReady.value)
        assertEquals(generation, session.styleGeneration.value)
    }

    @Test
    fun aFailedDiffAfterASameBaseSwitchKeepsTheLoadedStyleId() {
        val session = session()
        session.applyStyleDiff(StyleDiff(LOADED_STYLE, listOf(background("kept"))))
        val before = session.layers.value
        session.applyStyle(style(StyleId("trail")))

        val result =
            session.applyStyleDiff(
                StyleDiff(
                    StyleId("trail"),
                    listOf(
                        SlottedLayer(
                            LayerSlot.Base,
                            lineSpec("bad-line", JsonObject(mapOf("line-unknown" to JsonPrimitive(1)))),
                        ),
                    ),
                ),
            )

        assertEquals(Outcome.Failure(AppError.Unsupported), result)
        assertEquals(LOADED_STYLE, session.loadedStyleId)
        assertEquals(before, session.layers.value)
    }

    @Test
    fun setStyleWithTheLoadedStyleIsANoOp() {
        val session = session()
        session.onStyleLoaded()
        val loaded = session.style.value
        val generation = session.styleGeneration.value

        assertEquals(Outcome.Success(Unit), session.applyStyle(style(LOADED_STYLE)))

        assertEquals(loaded, session.style.value)
        assertTrue("a redundant setStyle must not clear styleReady", session.styleReady.value)
        assertEquals(generation, session.styleGeneration.value)
    }

    @Test
    fun setStyleRejectsABlankDocumentAndKeepsTheCurrentStyle() {
        val session = session()
        val before = session.style.value

        val result = session.applyStyle(StyleSpec(StyleId("trail"), "  "))

        assertEquals(Outcome.Failure(AppError.InvalidInput), result)
        assertEquals(LOADED_STYLE, session.loadedStyleId)
        assertEquals(before, session.style.value)
    }

    @Test
    fun setStyleRejectsANonObjectDocumentAndKeepsTheCurrentStyle() {
        val session = session()
        val before = session.style.value

        val result = session.applyStyle(StyleSpec(StyleId("trail"), "[1, 2, 3]"))

        assertEquals(Outcome.Failure(AppError.InvalidInput), result)
        assertEquals(LOADED_STYLE, session.loadedStyleId)
        assertEquals(before, session.style.value)
    }

    @Test
    fun addLayerCompilesBeforeStoring() {
        val session = session()
        val spec = backgroundSpec("valid")

        val result = session.addLayer(LayerSlot.Base, spec, PluginId("example"))

        assertEquals(Outcome.Success(Unit), result)
        assertEquals(listOf(LayerId("valid")), session.layers.value.map { it.spec.id })
        assertEquals(
            PluginId("example"),
            session.layers.value
                .single()
                .owner,
        )
    }

    @Test
    fun addLayerReplacesALayerWithTheSameIdWhereverItMoved() {
        val session = session()

        session.addLayer(LayerSlot.Base, backgroundSpec("same"), PluginId("example"))
        session.addLayer(LayerSlot.Underlay, backgroundSpec("same"), PluginId("example"))

        assertEquals(1, session.layers.value.size)
        assertEquals(
            LayerSlot.Underlay,
            session.layers.value
                .single()
                .slot,
        )
    }

    @Test
    fun addLayerRejectsAnUnsupportedProperty() {
        val session = session()

        val result =
            session.addLayer(
                LayerSlot.Base,
                lineSpec(
                    id = "bad-line",
                    paint = JsonObject(mapOf("line-unknown" to JsonPrimitive(1))),
                ),
                PluginId("example"),
            )

        assertEquals(Outcome.Failure(AppError.Unsupported), result)
        assertTrue(session.layers.value.isEmpty())
    }

    @Test
    fun aMalformedLegacyFilterIsReportedNotThrown() {
        val report =
            StyleLayerCompiler.compileAll(
                listOf(
                    SlottedLayer(
                        LayerSlot.Base,
                        lineSpec(id = "malformed", filter = malformedFilter()),
                    ),
                ),
            )

        assertTrue(report.compiled.isEmpty())
        assertEquals(1, report.unsupported.size)
        assertTrue(
            report.unsupported
                .single()
                .reason
                .isNotEmpty(),
        )
    }

    @Test
    fun aMalformedLegacyFilterFailsTheDiffAndKeepsLayers() {
        val session = session()
        session.applyStyleDiff(StyleDiff(LOADED_STYLE, listOf(background("kept"))))
        val before = session.layers.value

        val result =
            session.applyStyleDiff(
                StyleDiff(
                    LOADED_STYLE,
                    listOf(SlottedLayer(LayerSlot.Base, lineSpec("malformed", filter = malformedFilter()))),
                ),
            )

        assertEquals(Outcome.Failure(AppError.Unsupported), result)
        assertEquals(before, session.layers.value)
    }

    @Test
    fun updateTracksReplacesTheDrawnLibrary() {
        val session = session()
        val first = MapTrack(id = "a", segments = listOf(listOf(GeoPoint(0.0, 0.0))))
        val second = MapTrack(id = "b", segments = listOf(listOf(GeoPoint(1.0, 1.0))))

        session.updateTracks(listOf(first))
        assertEquals(listOf(first), session.tracks.value)

        session.updateTracks(listOf(first, second))
        assertEquals(2, session.tracks.value.size)

        session.updateTracks(emptyList())
        assertTrue(session.tracks.value.isEmpty())
    }

    @Test
    fun aNewAppSourceReplacesThePlanAndKeepsTheLoadedStyle() {
        val session = MapHostState(declaredStyle(), CAMERA) { null }
        session.onStyleLoaded()
        val document = session.style.value.json
        val tiles = MapTileSource { _, _, _ -> EMPTY_TILE }

        val result = session.applySources(EMPTY_RESOLVER, mapOf(MapSourceIds.REGION to tiles))

        assertEquals(Outcome.Success(Unit), result)
        assertSame(
            tiles,
            session.style.value.plan.app
                .single()
                .tiles,
        )
        assertEquals("an app source is not in the document, so nothing reloads", document, session.style.value.json)
        assertTrue("no reload is pending, so the layers stay ready", session.styleReady.value)
        assertEquals(LOADED_STYLE, session.loadedStyleId)
    }

    @Test
    fun aUriSourceAppearingWaitsForTheStyleReload() {
        val session = MapHostState(declaredStyle(), CAMERA) { null }
        session.onStyleLoaded()

        val result = session.applySources({ id -> "pmtiles://file:///$id.pmtiles" }, emptyMap())

        assertEquals(Outcome.Success(Unit), result)
        assertEquals("pmtiles://file:///region.pmtiles", session.style.value.plan.uris[MapSourceIds.REGION])
        assertTrue("the engine reloads the changed document, so ready must wait", !session.styleReady.value)
    }

    @Test
    fun theSameSourcesChangeNothing() {
        val session = MapHostState(declaredStyle(), CAMERA) { null }
        session.onStyleLoaded()
        val tiles = MapTileSource { _, _, _ -> EMPTY_TILE }
        session.applySources(EMPTY_RESOLVER, mapOf(MapSourceIds.REGION to tiles))
        val applied = session.style.value

        val result = session.applySources(EMPTY_RESOLVER, mapOf(MapSourceIds.REGION to tiles))

        assertEquals(Outcome.Success(Unit), result)
        assertSame("a repeated set must not rebuild the style", applied, session.style.value)
    }

    @Test
    fun aStyleSwitchKeepsTheAppSources() {
        val session = MapHostState(declaredStyle(), CAMERA) { null }
        val tiles = MapTileSource { _, _, _ -> EMPTY_TILE }
        session.applySources(EMPTY_RESOLVER, mapOf(MapSourceIds.REGION to tiles))

        session.applyStyle(StyleSpec(LOADED_STYLE, TRAIL_DOCUMENT, sources = declaredSources()))

        assertSame(
            tiles,
            session.style.value.plan.app
                .single()
                .tiles,
        )
    }

    @Test
    fun aSourceRefreshBuildsFromTheStyleLastSet() {
        val session = MapHostState(declaredStyle(), CAMERA) { null }
        session.applyStyle(StyleSpec(LOADED_STYLE, TRAIL_DOCUMENT, sources = declaredSources()))
        val tiles = MapTileSource { _, _, _ -> EMPTY_TILE }

        session.applySources(EMPTY_RESOLVER, mapOf(MapSourceIds.REGION to tiles))

        val document = Json.parseToJsonElement(session.style.value.json.json).jsonObject
        assertEquals("trail", document.getValue("glyphs").jsonPrimitive.content)
        assertSame(
            tiles,
            session.style.value.plan.app
                .single()
                .tiles,
        )
    }

    private fun session(): MapHostState = MapHostState(style(LOADED_STYLE), CAMERA) { null }

    private fun style(id: StyleId): StyleSpec = StyleSpec(id = id, baseStyleJson = VALID_STYLE)

    private fun declaredStyle(): StyleSpec = StyleSpec(id = LOADED_STYLE, baseStyleJson = VALID_STYLE, sources = declaredSources())

    private fun declaredSources(): List<MapSource> = listOf(MapSource(MapSourceIds.REGION, minZoom = 8, maxZoom = 14))

    private fun background(id: String): SlottedLayer = SlottedLayer(LayerSlot.Base, backgroundSpec(id))

    private fun backgroundSpec(id: String): LayerSpec =
        LayerSpec(
            id = LayerId(id),
            type = LayerType.Background,
            paint = JsonObject(mapOf("background-color" to JsonPrimitive("#cccccc"))),
        )

    private fun lineSpec(
        id: String,
        paint: JsonObject = JsonObject(mapOf("line-color" to JsonPrimitive("#cccccc"))),
        filter: JsonElement? = null,
    ): LayerSpec =
        LayerSpec(
            id = LayerId(id),
            type = LayerType.Line,
            source = MapSourceIds.REGION,
            filter = filter,
            paint = paint,
        )

    private fun malformedFilter(): JsonArray = JsonArray(listOf(JsonPrimitive("has")))

    private companion object {
        val LOADED_STYLE: StyleId = StyleId("meadow")
        const val VALID_STYLE = """{"version":8}"""
        const val TRAIL_DOCUMENT = """{"version":8,"glyphs":"trail"}"""
        val CAMERA = Camera(center = GeoPoint(0.0, 0.0), zoom = 1.0)
        val EMPTY_RESOLVER: (SourceId) -> String? = { null }
        val EMPTY_TILE = ByteArray(0)
    }
}
