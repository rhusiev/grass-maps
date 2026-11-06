package nl.r1a.grassmap.map

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmap.map.style.StyleLayerCompiler
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LayerId
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerSpec
import nl.r1a.grassmap.plugin.api.model.LayerType
import nl.r1a.grassmap.plugin.api.model.MapSourceIds
import nl.r1a.grassmap.plugin.api.model.SlottedLayer
import nl.r1a.grassmap.plugin.api.model.StyleDiff
import nl.r1a.grassmap.plugin.api.model.StyleId
import nl.r1a.grassmap.plugin.api.model.StyleSpec
import org.junit.Assert.assertEquals
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
                StyleDiff(StyleId("touring"), listOf(background("stale"))),
            )

        assertEquals(Outcome.Failure(AppError.InvalidInput), result)
        assertEquals(before, session.layers.value)
    }

    @Test
    fun setStyleChangesWhichDiffIsAccepted() {
        val session = session()

        assertEquals(Outcome.Success(Unit), session.applyStyle(style(StyleId("touring"))))
        assertEquals(StyleId("touring"), session.loadedStyleId)
        assertEquals(
            Outcome.Failure(AppError.InvalidInput),
            session.applyStyleDiff(StyleDiff(LOADED_STYLE, listOf(background("stale")))),
        )
        assertEquals(
            Outcome.Success(Unit),
            session.applyStyleDiff(StyleDiff(StyleId("touring"), listOf(background("new")))),
        )
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
                                type = LayerType.Circle,
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
    fun setStyleRejectsABlankDocumentAndKeepsTheCurrentStyle() {
        val session = session()
        val before = session.style.value

        val result = session.applyStyle(StyleSpec(StyleId("touring"), "  "))

        assertEquals(Outcome.Failure(AppError.InvalidInput), result)
        assertEquals(LOADED_STYLE, session.loadedStyleId)
        assertEquals(before, session.style.value)
    }

    @Test
    fun setStyleRejectsANonObjectDocumentAndKeepsTheCurrentStyle() {
        val session = session()
        val before = session.style.value

        val result = session.applyStyle(StyleSpec(StyleId("touring"), "[1, 2, 3]"))

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

    private fun session(): MapHostState = MapHostState(style(LOADED_STYLE), Camera(center = GeoPoint(0.0, 0.0), zoom = 1.0)) { null }

    private fun style(id: StyleId): StyleSpec = StyleSpec(id = id, baseStyleJson = VALID_STYLE)

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
        val LOADED_STYLE: StyleId = StyleId("standard")
        const val VALID_STYLE = """{"version":8}"""
    }
}
