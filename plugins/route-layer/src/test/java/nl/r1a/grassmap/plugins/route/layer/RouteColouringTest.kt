package nl.r1a.grassmap.plugins.route.layer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.MapEvent
import nl.r1a.grassmap.plugin.api.host.MapHost
import nl.r1a.grassmap.plugin.api.model.AttributeValue
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.CameraUpdate
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LayerId
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerSpec
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.model.MapTrack
import nl.r1a.grassmap.plugin.api.model.RouteLinePattern
import nl.r1a.grassmap.plugin.api.model.RouteLineStyle
import nl.r1a.grassmap.plugin.api.model.ScreenOffset
import nl.r1a.grassmap.plugin.api.model.StyleDiff
import nl.r1a.grassmap.plugin.api.model.StyleSpec
import nl.r1a.grassmap.plugin.api.model.TrackingMode
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteAttributes
import nl.r1a.grassmap.plugin.api.provider.RouteSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteColouringTest {
    @Test
    fun theSlopeScaleIsTheRound10SevenStopsInBothThemes() {
        assertEquals(
            listOf("#0D366B", "#256ABF", "#6DA7EC", "#D8D6D0", "#EE8C80", "#D9473E", "#8F1D1A"),
            RouteRound10Palette.slopeColours(dark = false),
        )
        assertEquals(
            listOf("#B7D3F6", "#5598E7", "#1F5AA3", "#7C7F84", "#B03A34", "#EE6F65", "#FFC4BC"),
            RouteRound10Palette.slopeColours(dark = true),
        )
    }

    @Test
    fun aGradeFallsInTheDesignsBinBelowTheStopItDoesNotPass() {
        assertEquals(0, RouteRound10Palette.slopeBin(-16.0))
        assertEquals(0, RouteRound10Palette.slopeBin(-15.0))
        assertEquals(1, RouteRound10Palette.slopeBin(-12.0))
        assertEquals(2, RouteRound10Palette.slopeBin(-5.0))
        assertEquals(3, RouteRound10Palette.slopeBin(0.0))
        assertEquals(3, RouteRound10Palette.slopeBin(3.0))
        assertEquals(4, RouteRound10Palette.slopeBin(3.5))
        assertEquals(5, RouteRound10Palette.slopeBin(9.0))
        assertEquals(5, RouteRound10Palette.slopeBin(15.0))
        assertEquals(6, RouteRound10Palette.slopeBin(15.1))
    }

    @Test
    fun slopeModePicksTheBinColourInTheLightAndDarkPalettes() {
        assertEquals("#EE8C80", RouteColouring.colour(segment(grade = 3.5), RouteColourMode.Slope, dark = false))
        assertEquals("#B03A34", RouteColouring.colour(segment(grade = 3.5), RouteColourMode.Slope, dark = true))
        assertEquals("#256ABF", RouteColouring.colour(segment(grade = -12.0), RouteColourMode.Slope, dark = false))
        assertEquals("#D8D6D0", RouteColouring.colour(segment(grade = 0.0), RouteColourMode.Slope, dark = false))
    }

    @Test
    fun surfaceModeUsesTheRound10SurfaceColours() {
        assertEquals("#4A3AA7", RouteColouring.colour(segment(surface = "asphalt"), RouteColourMode.Surface, dark = false))
        assertEquals("#9085E9", RouteColouring.colour(segment(surface = "asphalt"), RouteColourMode.Surface, dark = true))
        assertEquals("#E87BA4", RouteColouring.colour(segment(surface = "paving_stones"), RouteColourMode.Surface, dark = false))
        assertEquals("#008300", RouteColouring.colour(segment(surface = "ground"), RouteColourMode.Surface, dark = false))
        assertEquals("#EDA100", RouteColouring.colour(segment(surface = "compacted"), RouteColourMode.Surface, dark = false))
        assertEquals("#C98500", RouteColouring.colour(segment(surface = "gravel"), RouteColourMode.Surface, dark = true))
    }

    @Test
    fun aSurfaceThePaletteHasNoClassForKeepsTheExistingLineColour() {
        assertEquals(
            RouteRound10Palette.UNCLASSIFIED,
            RouteColouring.colour(segment(surface = "wood"), RouteColourMode.Surface, dark = false),
        )
    }

    @Test
    fun unpavedIsDashedAndPavedIsSolid() {
        assertEquals(RouteLinePattern.Dashed, RouteColouring.pattern(segment(surface = "gravel")))
        assertEquals(RouteLinePattern.Dashed, RouteColouring.pattern(segment(surface = "ground")))
        assertEquals(RouteLinePattern.Solid, RouteColouring.pattern(segment(surface = "asphalt")))
        assertEquals(RouteLinePattern.Solid, RouteColouring.pattern(segment(surface = "paving_stones")))
    }

    @Test
    fun aSurfaceValueThePaletteDoesNotKnowIsNotDashed() {
        assertEquals(RouteLinePattern.Solid, RouteColouring.pattern(segment(surface = "wood")))
        assertEquals(RouteLinePattern.Solid, RouteColouring.pattern(segment()))
    }

    @Test
    fun stepsProduceStripesAndWinOverAnUnpavedSurface() {
        assertEquals(RouteLinePattern.Striped, RouteColouring.pattern(segment(highway = "steps")))
        assertEquals(RouteLinePattern.Striped, RouteColouring.pattern(segment(highway = "stairs")))
        assertEquals(
            RouteLinePattern.Striped,
            RouteColouring.pattern(segment(surface = "ground", highway = "steps")),
        )
    }

    @Test
    fun aRouteWithNoClassAttributeKeepsThePlainLine() {
        val route =
            route(
                segment(0, 1),
                segment(1, 2, name = "only a name"),
            )

        val style = RouteColouring.style(route, RouteColourMode.Slope, dark = false)

        assertTrue(!style.isColoured)
    }

    @Test
    fun aRouteWithOneUnclassifiedSegmentKeepsTheExistingColourForThatPart() {
        val route = route(segment(0, 1, grade = 2.0), segment(1, 4))

        val style = RouteColouring.style(route, RouteColourMode.Slope, dark = false)

        assertEquals(2, style.runs.size)
        assertEquals("#D8D6D0", style.runs[0].colour)
        assertEquals(RouteRound10Palette.UNCLASSIFIED, style.runs[1].colour)
        assertEquals(RouteLinePattern.Solid, style.runs[1].pattern)
    }

    @Test
    fun aMixedSegmentSetKeepsTheEngineOrderAndCoversTheWholeLine() {
        val route =
            route(
                segment(0, 1, grade = 3.5, surface = "asphalt", highway = "residential"),
                segment(1, 2, grade = 9.0, surface = "gravel", highway = "track"),
                segment(2, 3, grade = -12.0, surface = "asphalt", highway = "steps"),
                segment(3, 4, grade = 0.5, surface = "ground", highway = "path"),
            )

        val style = RouteColouring.style(route, RouteColourMode.Slope, dark = false)

        assertEquals(listOf(0, 1, 2, 3), style.runs.map { it.startIndex })
        assertEquals(listOf(1, 2, 3, 4), style.runs.map { it.endIndex })
        assertEquals(
            listOf(
                RouteLinePattern.Solid,
                RouteLinePattern.Dashed,
                RouteLinePattern.Striped,
                RouteLinePattern.Dashed,
            ),
            style.runs.map { it.pattern },
        )
        assertEquals(
            listOf("#EE8C80", "#D9473E", "#256ABF", "#D8D6D0"),
            style.runs.map { it.colour },
        )
        assertEquals(RouteRound10Palette.STRIPE_LIGHT, style.stripeColour)
    }

    @Test
    fun aGapBetweenAnnotatedSegmentsIsFilledWithTheExistingColour() {
        val route = route(segment(0, 1, grade = 2.0), segment(3, 4, grade = 2.0))

        val style = RouteColouring.style(route, RouteColourMode.Slope, dark = false)

        assertEquals(listOf(0, 1, 3), style.runs.map { it.startIndex })
        assertEquals(listOf(1, 3, 4), style.runs.map { it.endIndex })
        assertEquals(RouteRound10Palette.UNCLASSIFIED, style.runs[1].colour)
    }

    @Test
    fun aLineThatEndsBeforeTheLastGeometryPointGetsATailRun() {
        val route = route(segment(0, 1, grade = 2.0))

        val style = RouteColouring.style(route, RouteColourMode.Slope, dark = false)

        assertEquals(2, style.runs.size)
        assertEquals(1, style.runs[1].startIndex)
        assertEquals(4, style.runs[1].endIndex)
        assertEquals(RouteRound10Palette.UNCLASSIFIED, style.runs[1].colour)
    }

    @Test
    fun theDarkPaletteAlsoCarriesTheDarkStripeInk() {
        val route = route(segment(0, 4, grade = 1.0, surface = "ground", highway = "path"))

        val style = RouteColouring.style(route, RouteColourMode.Slope, dark = true)

        assertEquals(RouteRound10Palette.STRIPE_DARK, style.stripeColour)
    }

    @Test
    fun theLayerDrawsTheStyleAndTheRouteOnOneHostWrite() =
        runTest {
            val host = RecordingMapHost()

            val outcome =
                RouteLayer({ host })
                    .draw(route(segment(0, 4, grade = 2.0)), RouteLineDrawing.Coloured(mode = RouteColourMode.Slope))

            assertEquals(Outcome.Success(Unit), outcome)
            assertEquals(1, host.draws.size)
            assertEquals(
                1,
                host.draws
                    .single()
                    .second.runs.size,
            )
        }

    @Test
    fun thePlainDrawingLeavesTheHostsSingleColourLineInPlace() =
        runTest {
            val host = RecordingMapHost()

            RouteLayer({ host }).draw(route(segment(0, 4, grade = 2.0)), RouteLineDrawing.Plain)

            assertFalse(
                host.draws
                    .single()
                    .second.isColoured,
            )
        }

    @Test
    fun aMissingHostIsATypedFailure() =
        runTest {
            val outcome =
                RouteLayer({ null })
                    .draw(route(segment(0, 4, grade = 2.0)), RouteLineDrawing.Coloured(mode = RouteColourMode.Slope))

            assertEquals(Outcome.Failure(AppError.NotReady), outcome)
        }

    private fun route(vararg segments: RouteSegment): Route =
        Route(
            geometry =
                listOf(
                    GeoPoint(49.8400, 24.0300),
                    GeoPoint(49.8401, 24.0301),
                    GeoPoint(49.8402, 24.0302),
                    GeoPoint(49.8403, 24.0303),
                    GeoPoint(49.8404, 24.0304),
                ),
            distanceMeters = 60.0,
            durationSeconds = 43.0,
            segments = segments.toList(),
        )

    private fun segment(
        start: Int = 0,
        end: Int = 4,
        grade: Double? = null,
        surface: String? = null,
        highway: String? = null,
        name: String? = null,
    ): RouteSegment {
        val attributes =
            buildMap {
                grade?.let { put(RouteAttributes.GRADE, AttributeValue.NumberValue(it)) }
                surface?.let { put(RouteAttributes.SURFACE, AttributeValue.TextValue(it)) }
                highway?.let { put(RouteAttributes.HIGHWAY, AttributeValue.TextValue(it)) }
                name?.let { put("name", AttributeValue.TextValue(it)) }
            }
        return RouteSegment(
            startIndex = start,
            endIndex = end,
            distanceMeters = 10.0,
            durationSeconds = 7.0,
            attributes = attributes,
        )
    }

    private class RecordingMapHost : MapHost {
        private val state = MutableStateFlow(Camera(center = GeoPoint(49.84, 24.03), zoom = 14.0))
        val draws = mutableListOf<Pair<Route?, RouteLineStyle>>()

        override val camera: StateFlow<Camera> = state
        override val events: Flow<MapEvent> = emptyFlow()

        override suspend fun setStyle(style: StyleSpec): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun applyStyleDiff(diff: StyleDiff): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun addLayer(
            slot: LayerSlot,
            spec: LayerSpec,
            owner: PluginId,
        ): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun removeLayers(owner: PluginId): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun removeLayer(layer: LayerId): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun visibleBounds(): BoundingBox? = null

        override suspend fun moveCamera(update: CameraUpdate): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun animateCamera(update: CameraUpdate): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun fitBounds(bounds: BoundingBox): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun setTrackingMode(mode: TrackingMode): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun setLocation(fix: LocationFix?): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun setRoute(route: Route?): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun setRoute(
            route: Route?,
            style: RouteLineStyle,
        ): Outcome<Unit> {
            draws += route to style
            return Outcome.Success(Unit)
        }

        override suspend fun setTracks(tracks: List<MapTrack>): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun setPositionOffset(offset: ScreenOffset): Outcome<Unit> = Outcome.Success(Unit)
    }
}
