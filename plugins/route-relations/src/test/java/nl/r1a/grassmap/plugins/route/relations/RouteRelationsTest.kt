package nl.r1a.grassmap.plugins.route.relations

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.MapEvent
import nl.r1a.grassmap.plugin.api.host.MapHost
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.CameraUpdate
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.HikingNetwork
import nl.r1a.grassmap.plugin.api.model.LayerId
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerSpec
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.model.MapTrack
import nl.r1a.grassmap.plugin.api.model.RouteRelation
import nl.r1a.grassmap.plugin.api.model.RouteRelationMember
import nl.r1a.grassmap.plugin.api.model.RouteRelations
import nl.r1a.grassmap.plugin.api.model.RouteRelationsJson
import nl.r1a.grassmap.plugin.api.model.ScreenOffset
import nl.r1a.grassmap.plugin.api.model.StyleDiff
import nl.r1a.grassmap.plugin.api.model.StyleSpec
import nl.r1a.grassmap.plugin.api.model.TrackingMode
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.testing.fixture.TestRegions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RouteRelationsTest {
    private val fixture: RouteRelations by lazy {
        val file = TestRegions.repositoryRoot().resolve("test-data/regions/fixture-mini/2026-11-03-1/relations.json")
        when (val outcome = RouteRelationsJson.decode(Files.readString(file))) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> error("fixture relations unreadable: ${outcome.error}")
        }
    }

    @Test
    fun theComponentCarriesTheFixtureRelationsAndTheirOrder() {
        assertEquals(listOf(9101L, 9102L, 9107L), fixture.relations.map { it.id })
        val first = fixture.relations.first()
        assertEquals(listOf(896757861L, 306496773L), first.members.map { it.wayId })
        assertEquals(HikingNetwork.Local, first.network)
        assertEquals("red:white:red_bar", first.osmcSymbol)
        assertEquals(3, fixture.ways.size)
    }

    @Test
    fun assemblyKeepsMemberOrderAndReversesAWayThatPointsBack() {
        val forward = listOf(GeoPoint(1.0, 1.0), GeoPoint(1.0, 2.0))
        val reversed = listOf(GeoPoint(1.0, 4.0), GeoPoint(1.0, 3.0))
        val component =
            RouteRelations(
                ways = mapOf(1L to forward, 2L to reversed),
                relations =
                    listOf(
                        RouteRelation(
                            id = 1L,
                            route = "hiking",
                            network = HikingNetwork.Local,
                            ref = null,
                            name = null,
                            osmcSymbol = null,
                            colour = null,
                            distanceMeters = null,
                            members = listOf(RouteRelationMember(1L, null), RouteRelationMember(2L, null)),
                        ),
                    ),
            )

        val assembled = RouteRelationsGeometry.assemble(component).single()

        assertEquals(listOf(1L, 2L), assembled.segments.map { it.wayId })
        assertEquals(listOf(1.0, 2.0), assembled.segments[0].points.map { it.longitude })
        assertEquals(listOf(3.0, 4.0), assembled.segments[1].points.map { it.longitude })
    }

    @Test
    fun aSharedWayGivesEachRelationItsOwnLane() {
        val assembled = RouteRelationsGeometry.assemble(fixture)

        val shared =
            assembled
                .flatMap { it.segments }
                .filter { it.wayId == 896757861L }
        assertEquals(listOf(0, 1), shared.map { it.laneRank })
        assertTrue(shared.all { it.laneCount == 2 })
    }

    @Test
    fun osmcSymbolsMapTheDocumentedShapesAndRefuseUnknownOnes() {
        val redBar = OsmcSymbols.parse("red:white:red_bar")
        assertNotNull(redBar)
        assertEquals("osmc_bg_square", redBar?.background?.image)
        assertEquals("#ffffff", redBar?.background?.color)
        assertEquals("osmc_fg_bar", redBar?.foreground?.image)
        assertEquals("#d02c2c", redBar?.foreground?.color)

        assertEquals("osmc_bg_square", OsmcSymbols.parse("red:white:red_circle:1:black")?.background?.image)
        assertEquals("osmc_bg_circle", OsmcSymbols.parse("red:white:red_circle:1:black")?.foreground?.image)
        assertEquals("osmc_fg_diamond", OsmcSymbols.parse("green:white:green_diamond")?.foreground?.image)
        assertEquals("osmc_fg_backslash", OsmcSymbols.parse("green:white:green_backslash")?.foreground?.image)

        assertNull("a shell is not one of our shapes", OsmcSymbols.parse("blue:blue:shell_modern"))
        assertNull("a mark without a plate is malformed", OsmcSymbols.parse("red_round"))
        assertNull("a route colour and a plate need a foreground", OsmcSymbols.parse("red:white"))
        assertNull("no symbol at all", OsmcSymbols.parse(null))
    }

    @Test
    fun theLayerOutputHasOneBandAndASymbolPairPerLane() {
        val layers = RouteRelationLayers.build(fixture)

        assertEquals(
            RouteRelationLayers.BAND_LAYER_ID,
            layers
                .first()
                .spec.id.value,
        )
        val band = layers.first().spec.data ?: error("band has no data")
        val width =
            layers
                .first()
                .spec.paint
                .getValue("line-width")
        assertEquals(
            "the zoom expression must be the top-level interpolate input",
            "interpolate",
            (width as JsonArray)[0].jsonPrimitive.content,
        )
        assertTrue(band.contains("\"laneRank\":0"))
        assertTrue(band.contains("\"laneRank\":1"))
        assertTrue(band.contains("\"laneOffsetUnits\":-0.2500000"))
        assertTrue(band.contains("\"laneOffsetUnits\":0.2500000"))
        assertEquals(
            listOf(
                "route.relations.band",
                "route.relations.symbol.bg.0",
                "route.relations.symbol.fg.0",
                "route.relations.symbol.bg.1",
                "route.relations.symbol.fg.1",
            ),
            layers.map { it.spec.id.value },
        )
        val symbol = layers.first { it.spec.id.value == "route.relations.symbol.bg.0" }.spec
        assertEquals("osmc_bg_square", firstProperty(symbol, "bgIcon"))
        assertEquals("osmc_fg_bar", firstProperty(symbol, "fgIcon"))
    }

    @Test
    fun theControllerReplacesItsOwnLayersAndClearsOnFailure() =
        runTest {
            val host = RecordingMapHost()
            val controller = RouteRelationsMapController(mapHost = host, load = { Outcome.Success(fixture) })

            assertEquals(Outcome.Success(Unit), controller.refresh())
            assertEquals(5, host.layers.size)
            assertEquals(Outcome.Success(Unit), controller.refresh())
            assertEquals("a refresh must not stack duplicates", 5, host.layers.size)

            val failing = RouteRelationsMapController(mapHost = host, load = { Outcome.Failure(AppError.CorruptData) })
            assertEquals(Outcome.Failure(AppError.CorruptData), failing.refresh())
            assertTrue(host.layers.isEmpty())
        }

    private fun firstProperty(
        spec: LayerSpec,
        name: String,
    ): String? {
        val data = spec.data ?: return null
        val feature =
            Json
                .parseToJsonElement(data)
                .jsonObject
                .getValue("features")
                .jsonArray
                .first()
                .jsonObject
        return feature
            .getValue("properties")
            .jsonObject[name]
            ?.jsonPrimitive
            ?.content
    }
}

/** A MapHost that records route-slot layers and ignores everything else. */
private class RecordingMapHost : MapHost {
    val layers = mutableListOf<Pair<LayerSlot, LayerSpec>>()

    override val camera: StateFlow<Camera> = MutableStateFlow(Camera(center = GeoPoint(0.0, 0.0), zoom = 1.0))

    override val events: Flow<MapEvent> = emptyFlow()

    override suspend fun setStyle(style: StyleSpec): Outcome<Unit> = Outcome.Success(Unit)

    override suspend fun applyStyleDiff(diff: StyleDiff): Outcome<Unit> = Outcome.Success(Unit)

    override suspend fun addLayer(
        slot: LayerSlot,
        spec: LayerSpec,
        owner: PluginId,
    ): Outcome<Unit> {
        layers += slot to spec
        return Outcome.Success(Unit)
    }

    override suspend fun removeLayers(owner: PluginId): Outcome<Unit> {
        layers.clear()
        return Outcome.Success(Unit)
    }

    override suspend fun removeLayer(layer: LayerId): Outcome<Unit> {
        layers.removeAll { it.second.id == layer }
        return Outcome.Success(Unit)
    }

    override suspend fun visibleBounds(): BoundingBox? = null

    override suspend fun moveCamera(update: CameraUpdate): Outcome<Unit> = Outcome.Success(Unit)

    override suspend fun animateCamera(update: CameraUpdate): Outcome<Unit> = Outcome.Success(Unit)

    override suspend fun fitBounds(bounds: BoundingBox): Outcome<Unit> = Outcome.Success(Unit)

    override suspend fun setTrackingMode(mode: TrackingMode): Outcome<Unit> = Outcome.Success(Unit)

    override suspend fun setLocation(fix: LocationFix?): Outcome<Unit> = Outcome.Success(Unit)

    override suspend fun setRoute(route: Route?): Outcome<Unit> = Outcome.Success(Unit)

    override suspend fun setTracks(tracks: List<MapTrack>): Outcome<Unit> = Outcome.Success(Unit)

    override suspend fun setPositionOffset(offset: ScreenOffset): Outcome<Unit> = Outcome.Success(Unit)
}
