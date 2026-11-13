package nl.r1a.grassmap.plugins.route.relations

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
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
import nl.r1a.grassmap.plugin.api.model.LayerId
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerSpec
import nl.r1a.grassmap.plugin.api.model.LayerType
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.model.MapTrack
import nl.r1a.grassmap.plugin.api.model.Route
import nl.r1a.grassmap.plugin.api.model.RouteNetwork
import nl.r1a.grassmap.plugin.api.model.RouteRelation
import nl.r1a.grassmap.plugin.api.model.RouteRelationMember
import nl.r1a.grassmap.plugin.api.model.RouteRelations
import nl.r1a.grassmap.plugin.api.model.RouteRelationsJson
import nl.r1a.grassmap.plugin.api.model.ScreenOffset
import nl.r1a.grassmap.plugin.api.model.StyleDiff
import nl.r1a.grassmap.plugin.api.model.StyleSpec
import nl.r1a.grassmap.plugin.api.model.TrackingMode
import nl.r1a.grassmap.plugin.api.testing.fixture.TestRegions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import nl.r1a.grassmap.plugin.api.provider.Route as ProviderRoute

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
        assertEquals(listOf(9101L, 9102L, 9107L, 9108L, 9109L), fixture.relations.map { it.id })
        assertEquals(
            listOf(Route.Hiking, Route.Hiking, Route.Hiking, Route.Cycling, Route.Cycling),
            fixture.relations.map { it.route },
        )
        assertEquals(
            listOf(
                RouteNetwork.HikingLocal,
                RouteNetwork.HikingRegional,
                RouteNetwork.HikingNational,
                RouteNetwork.CyclingLocal,
                RouteNetwork.CyclingNational,
            ),
            fixture.relations.map { it.network },
        )
        val first = fixture.relations.first()
        assertEquals(listOf(896757861L, 306496773L), first.members.map { it.wayId })
        assertEquals("red:white:red_bar", first.osmcSymbol)
        assertEquals(3, fixture.ways.size)
        assertEquals(5, fixture.nodes.size)
    }

    @Test
    fun theFixtureCarriesTheCyclingNodeNetworkNumbers() {
        assertEquals(
            listOf(2344261512L, 3114545825L, 5556643401L, 5556643405L, 5556643624L),
            fixture.nodes.map { it.id },
        )
        assertEquals(
            listOf(
                RouteNetwork.CyclingRegional,
                RouteNetwork.CyclingLocal,
                RouteNetwork.CyclingLocal,
                RouteNetwork.CyclingLocal,
                RouteNetwork.CyclingLocal,
            ),
            fixture.nodes.map { it.network },
        )
        assertEquals(listOf("7", "48", "47", "13", "12"), fixture.nodes.map { it.reference })
        assertEquals(GeoPoint(49.94251, 23.9503673), fixture.nodes.last().point)
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
                            route = Route.Hiking,
                            network = RouteNetwork.HikingLocal,
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
        val cycleShared =
            assembled
                .flatMap { it.segments }
                .filter { it.wayId == 306496773L }
        assertEquals("a hiking and a cycling route share this way", listOf(0, 1), cycleShared.map { it.laneRank })
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
        assertEquals("osmc_fg_rectangle", OsmcSymbols.parse("blue:white:blue_rectangle")?.foreground?.image)

        assertNull("a shell is not one of our shapes", OsmcSymbols.parse("blue:blue:shell_modern"))
        assertNull("a mark without a plate is malformed", OsmcSymbols.parse("red_round"))
        assertNull("a route colour and a plate need a foreground", OsmcSymbols.parse("red:white"))
        assertNull("no symbol at all", OsmcSymbols.parse(null))
    }

    @Test
    fun theLayerOutputHasOneBandPerRouteKindAndASymbolPairPerLane() {
        val layers = RouteRelationLayers.build(fixture)

        assertEquals(
            listOf(
                "route.relations.band",
                "route.relations.cycling.band",
                "route.relations.symbol.bg.0",
                "route.relations.symbol.fg.0",
                "route.relations.symbol.bg.1",
                "route.relations.symbol.fg.1",
                "route.relations.node",
                "route.relations.node.text",
            ),
            layers.map { it.spec.id.value },
        )
        val hiking = layers.first().spec
        val cycling = layers.first { it.spec.id.value == RouteRelationLayers.CYCLING_BAND_LAYER_ID }.spec
        assertEquals(9f, hiking.minZoom)
        assertEquals(11f, cycling.minZoom)
        assertEquals(
            0.55f,
            hiking.paint
                .getValue("line-opacity")
                .jsonPrimitive.content
                .toFloat(),
        )
        assertEquals(
            0.45f,
            cycling.paint
                .getValue("line-opacity")
                .jsonPrimitive.content
                .toFloat(),
        )
        assertEquals(
            "the zoom expression must be the top-level interpolate input",
            "interpolate",
            (hiking.paint.getValue("line-width") as JsonArray)[0].jsonPrimitive.content,
        )
        assertTrue(hiking.data.orEmpty().contains("\"network\":\"lwn\""))
        assertTrue(cycling.data.orEmpty().contains("\"network\":\"lcn\""))
        assertTrue(hiking.data.orEmpty().contains("\"laneOffsetUnits\":-0.2500000"))
        assertTrue(hiking.data.orEmpty().contains("\"laneOffsetUnits\":0.2500000"))
        assertEquals(
            "cycling bands read the cycling palette",
            """["match",["get","network"],"icn","#d93030","ncn","#e0602a","rcn","#b03ac7","lcn","#2f5fe0","#2f5fe0"]""",
            cycling.paint.getValue("line-color").toString(),
        )
        val symbol = layers.first { it.spec.id.value == "route.relations.symbol.bg.0" }.spec
        assertEquals("osmc_bg_square", firstProperty(symbol, "bgIcon"))
        assertEquals("osmc_fg_bar", firstProperty(symbol, "fgIcon"))
    }

    @Test
    fun cyclingNodeNumbersDrawACircleAndTextAtTheirJunctions() {
        val layers = RouteRelationLayers.build(fixture)

        val circle = layers.first { it.spec.id.value == RouteRelationLayers.NODE_LAYER_ID }.spec
        assertEquals(LayerType.Circle, circle.type)
        assertEquals(15f, circle.minZoom)
        assertEquals(
            "#ffffff",
            circle.paint
                .getValue("circle-color")
                .jsonPrimitive.content,
        )
        assertEquals(
            "#2e9e44",
            circle.paint
                .getValue("circle-stroke-color")
                .jsonPrimitive.content,
        )
        assertEquals(
            "5.5",
            circle.paint
                .getValue("circle-radius")
                .jsonPrimitive.content,
        )
        assertTrue(circle.data.orEmpty().contains("\"ref\":\"47\""))
        assertTrue(circle.data.orEmpty().contains("\"network\":\"rcn\""))
        assertTrue(circle.data.orEmpty().contains("[23.9503673,49.9425100]"))

        val text = layers.first { it.spec.id.value == RouteRelationLayers.NODE_TEXT_LAYER_ID }.spec
        assertEquals(LayerType.Symbol, text.type)
        assertEquals(15f, text.minZoom)
        assertEquals(
            "#1d6b2e",
            text.paint
                .getValue("text-color")
                .jsonPrimitive.content,
        )
        assertEquals("get", (text.layout.getValue("text-field") as JsonArray)[0].jsonPrimitive.content)
        assertEquals("ref", (text.layout.getValue("text-field") as JsonArray)[1].jsonPrimitive.content)
        assertEquals(
            "point",
            text.layout
                .getValue("symbol-placement")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun aRouteKindWithoutRelationsDrawsNoBandForIt() {
        assertTrue(RouteRelationLayers.build(RouteRelations.EMPTY).isEmpty())

        val hikingOnly =
            fixture.copy(
                relations = fixture.relations.filter { it.route == Route.Hiking },
                nodes = emptyList(),
            )
        assertEquals(
            "no cycling band when the region carries no cycling route",
            listOf(
                "route.relations.band",
                "route.relations.symbol.bg.0",
                "route.relations.symbol.fg.0",
                "route.relations.symbol.bg.1",
                "route.relations.symbol.fg.1",
            ),
            RouteRelationLayers.build(hikingOnly).map { it.spec.id.value },
        )

        val cyclingOnly =
            fixture.copy(relations = fixture.relations.filter { it.route == Route.Cycling })
        assertEquals(
            listOf(
                "route.relations.cycling.band",
                "route.relations.symbol.bg.0",
                "route.relations.symbol.fg.0",
                "route.relations.node",
                "route.relations.node.text",
            ),
            RouteRelationLayers.build(cyclingOnly).map { it.spec.id.value },
        )
    }

    @Test
    fun anUnknownCyclingNetworkFallsBackToLocalAndUnknownRouteKindsAreCorrupt() {
        val local =
            RouteRelationsJson.decode(
                """
                {"format":"grass-maps-route-relations","version":1,
                 "ways":[{"id":1,"coordinates":[[23.94,49.94],[23.95,49.95]]}],
                 "relations":[{"id":9,"type":"route","route":"bicycle","network":"lwn",
                               "members":[{"way":1,"role":""}]}]}
                """.trimIndent(),
            )
        assertTrue(local is Outcome.Success)
        assertEquals(
            RouteNetwork.CyclingLocal,
            (local as Outcome.Success)
                .value.relations
                .single()
                .network,
        )

        val footRoute =
            RouteRelationsJson.decode(
                """
                {"format":"grass-maps-route-relations","version":1,
                 "ways":[{"id":1,"coordinates":[[23.94,49.94],[23.95,49.95]]}],
                 "relations":[{"id":9,"type":"route","route":"foot",
                               "members":[{"way":1,"role":""}]}]}
                """.trimIndent(),
            )
        assertEquals(Outcome.Failure(AppError.CorruptData), footRoute)

        val hikingNode =
            RouteRelationsJson.decode(
                """
                {"format":"grass-maps-route-relations","version":1,
                 "ways":[],"relations":[],
                 "nodes":[{"id":9,"network":"lwn","ref":"3","coordinates":[23.94,49.94]}]}
                """.trimIndent(),
            )
        assertEquals(Outcome.Failure(AppError.CorruptData), hikingNode)
    }

    @Test
    fun theControllerReplacesItsOwnLayersAndClearsOnFailure() =
        runTest {
            val host = RecordingMapHost()
            val controller = RouteRelationsMapController(mapHost = host, load = { Outcome.Success(fixture) })

            assertEquals(Outcome.Success(Unit), controller.refresh())
            assertEquals(8, host.layers.size)
            assertEquals(Outcome.Success(Unit), controller.refresh())
            assertEquals("a refresh must not stack duplicates", 8, host.layers.size)

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

    override suspend fun setRoute(route: ProviderRoute?): Outcome<Unit> = Outcome.Success(Unit)

    override suspend fun setTracks(tracks: List<MapTrack>): Outcome<Unit> = Outcome.Success(Unit)

    override suspend fun setPositionOffset(offset: ScreenOffset): Outcome<Unit> = Outcome.Success(Unit)
}
