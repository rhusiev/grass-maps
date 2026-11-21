package nl.r1a.grassmaps.plugins.favourites.layer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.r1a.grassmaps.plugin.api.contract.PluginId
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.host.MapEvent
import nl.r1a.grassmaps.plugin.api.host.MapHost
import nl.r1a.grassmaps.plugin.api.model.BoundingBox
import nl.r1a.grassmaps.plugin.api.model.Camera
import nl.r1a.grassmaps.plugin.api.model.CameraUpdate
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.LayerId
import nl.r1a.grassmaps.plugin.api.model.LayerSlot
import nl.r1a.grassmaps.plugin.api.model.LayerSpec
import nl.r1a.grassmaps.plugin.api.model.LocationFix
import nl.r1a.grassmaps.plugin.api.model.MapTrack
import nl.r1a.grassmaps.plugin.api.model.ScreenOffset
import nl.r1a.grassmaps.plugin.api.model.StyleDiff
import nl.r1a.grassmaps.plugin.api.model.StyleSpec
import nl.r1a.grassmaps.plugin.api.model.TrackingMode
import nl.r1a.grassmaps.plugin.api.provider.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavouriteLayerTest {
    @Test
    fun aGoldenListsEveryShapeAndIconWithItsName() {
        val library = FavouriteLayerLibrary(THREE_PLACES)
        val settings = FavouriteLayerSettings(showNames = true)

        val features = FavouriteLayerFeatures.visible(library, settings)
        assertEquals(listOf("Circle", "Square", "Octagon"), features.map { it.shape.name })
        assertEquals(listOf("Rynok", "Kava", "Park"), features.map { it.name })
        assertEquals(listOf("cafe", "bar", "park"), features.map { it.icon })
        assertEquals(
            listOf("osmc_bg_circle", "osmc_bg_square", "osmc_bg_round"),
            features.map { it.shape.sprite },
        )

        val collection = Json.parseToJsonElement(FavouriteLayerFeatures.featureCollection(library, settings)).jsonObject
        val jsonFeatures = collection["features"]!!.jsonArray
        assertEquals(3, jsonFeatures.size)
        val first = jsonFeatures[0].jsonObject["properties"]!!.jsonObject
        assertEquals("Rynok", first["name"]!!.jsonPrimitive.content)
        assertEquals("cafe", first["icon"]!!.jsonPrimitive.content)
        assertEquals("circle", first["shape"]!!.jsonPrimitive.content)
        assertEquals("cafe", first["iconSprite"]!!.jsonPrimitive.content)

        val layers = FavouriteLayerLayers.build(library, settings)
        assertEquals(
            listOf(
                FavouriteLayerLayers.SHAPE_LAYER_ID,
                FavouriteLayerLayers.ICON_LAYER_ID,
                FavouriteLayerLayers.NAME_LAYER_ID,
            ),
            layers.map { it.spec.id.value },
        )
        assertTrue(layers.all { it.slot == LayerSlot.Markers })
    }

    @Test
    fun aHiddenGroupLeavesNoRenderedFeatures() =
        runTest {
            val library =
                FavouriteLayerLibrary(
                    listOf(
                        marker("home", "Home", group = "Home"),
                        marker("work", "Work", group = "Work"),
                    ),
                )
            val settings = FavouriteLayerSettings(hiddenGroups = setOf("Home", "Work"))
            val host = RecordingMapHost()

            assertTrue(FavouriteLayerFeatures.visible(library, settings).isEmpty())
            val collection = Json.parseToJsonElement(FavouriteLayerFeatures.featureCollection(library, settings)).jsonObject
            assertEquals(0, collection["features"]!!.jsonArray.size)
            assertTrue(FavouriteLayerLayers.build(library, settings).isEmpty())

            val outcome =
                FavouritesLayerController(
                    library = { Outcome.Success(library) },
                    changes = emptyFlow(),
                    settings = MutableStateFlow(settings),
                    host = { host },
                ).refresh()

            assertEquals(Outcome.Success(Unit), outcome)
            assertEquals(0, host.added.size)
            assertTrue("the old layers must be removed", host.removed > 0)
        }

    @Test
    fun addRemoveAndRenameRedrawWithoutARestart() =
        runTest {
            var places = listOf(marker("a", "Old name"))
            val library = FavouriteLayerLibrary(places)
            val signals = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
            val settings = MutableStateFlow(FavouriteLayerSettings())
            val host = RecordingMapHost()
            val controller =
                FavouritesLayerController(
                    library = { Outcome.Success(FavouriteLayerLibrary(places)) },
                    changes = signals,
                    settings = settings,
                    host = { host },
                )
            val job = launch { controller.run() }
            advanceUntilIdle()
            assertEquals(1, host.drawCount)

            places = places + marker("b", "New place")
            signals.emit(Unit)
            advanceUntilIdle()
            assertEquals(2, host.drawCount)
            assertTrue(host.lastNames().contains("New place"))

            places = listOf(marker("a", "Renamed"), marker("b", "New place"))
            signals.emit(Unit)
            advanceUntilIdle()
            assertEquals(3, host.drawCount)
            assertTrue(host.lastNames().contains("Renamed"))

            places = listOf(marker("b", "New place"))
            signals.emit(Unit)
            advanceUntilIdle()
            assertEquals(4, host.drawCount)
            assertTrue("a removed place must not stay on the map", "Renamed" !in host.lastNames())

            settings.value = settings.value.copy(showNames = true)
            advanceUntilIdle()
            assertEquals(5, host.drawCount)
            assertTrue(host.added.any { it.id.value == FavouriteLayerLayers.NAME_LAYER_ID })
            job.cancel()
        }

    @Test
    fun aShapeChoiceOverridesEverySavedShapeAndTheIconCanBeHidden() {
        val library = FavouriteLayerLibrary(THREE_PLACES)
        val settings = FavouriteLayerSettings(markerShape = MarkerShape.Square, iconsPerPoint = false)

        val features = FavouriteLayerFeatures.visible(library, settings)
        assertEquals(listOf("square", "square", "square"), features.map { it.shape.wireName })
        assertEquals(
            listOf(FavouriteLayerLayers.SHAPE_LAYER_ID),
            FavouriteLayerLayers.build(library, settings).map { it.spec.id.value },
        )
    }

    @Test
    fun anUnknownIconUsesTheNeutralDot() {
        val library = FavouriteLayerLibrary(listOf(marker("a", "A", icon = "not-in-the-sheet")))
        val collection = Json.parseToJsonElement(FavouriteLayerFeatures.featureCollection(library, FavouriteLayerSettings())).jsonObject
        val properties = collection["features"]!!.jsonArray[0].jsonObject["properties"]!!.jsonObject

        assertEquals("osmc_fg_dot", properties["iconSprite"]!!.jsonPrimitive.content)
        assertEquals("not-in-the-sheet", properties["icon"]!!.jsonPrimitive.content)
    }

    @Test
    fun aMissingSurfaceIsNotReady() =
        runTest {
            val outcome =
                FavouritesLayerController(
                    library = { Outcome.Success(FavouriteLayerLibrary(THREE_PLACES)) },
                    changes = emptyFlow(),
                    settings = MutableStateFlow(FavouriteLayerSettings()),
                    host = { null },
                ).refresh()
            assertEquals(Outcome.Failure(AppError.NotReady), outcome)
        }

    private companion object {
        val THREE_PLACES =
            listOf(
                marker("a", "Rynok", icon = "cafe", shape = MarkerShape.Circle),
                marker("b", "Kava", icon = "bar", shape = MarkerShape.Square),
                marker("c", "Park", icon = "park", shape = MarkerShape.Octagon),
            )

        fun marker(
            id: String,
            name: String,
            group: String? = null,
            icon: String? = "cafe",
            shape: MarkerShape = MarkerShape.Circle,
        ) = FavouriteLayerMarker(
            id = id,
            name = name,
            group = group,
            position = GeoPoint(49.84, 24.03),
            color = "#d9483b",
            icon = icon,
            shape = shape,
        )
    }

    private class RecordingMapHost : MapHost {
        private val state = MutableStateFlow(Camera(center = GeoPoint(49.84, 24.03), zoom = 14.0))
        val added = mutableListOf<LayerSpec>()
        var removed = 0
        var drawCount = 0
            private set

        override val camera: StateFlow<Camera> = state
        override val events: Flow<MapEvent> = emptyFlow()

        override suspend fun setStyle(style: StyleSpec): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun applyStyleDiff(diff: StyleDiff): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun addLayer(
            slot: LayerSlot,
            spec: LayerSpec,
            owner: PluginId,
        ): Outcome<Unit> {
            added += spec
            return Outcome.Success(Unit)
        }

        override suspend fun removeLayers(owner: PluginId): Outcome<Unit> {
            removed += 1
            added.clear()
            drawCount += 1
            return Outcome.Success(Unit)
        }

        override suspend fun removeLayer(layer: LayerId): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun visibleBounds(): BoundingBox? = null

        override suspend fun moveCamera(update: CameraUpdate): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun animateCamera(update: CameraUpdate): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun fitBounds(bounds: BoundingBox): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun setTrackingMode(mode: TrackingMode): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun setLocation(fix: LocationFix?): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun setRoute(route: Route?): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun setTracks(tracks: List<MapTrack>): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun setPositionOffset(offset: ScreenOffset): Outcome<Unit> = Outcome.Success(Unit)

        fun lastNames(): List<String> {
            val data = added.firstOrNull { it.id.value == FavouriteLayerLayers.ICON_LAYER_ID }?.data ?: return emptyList()
            val collection = Json.parseToJsonElement(data).jsonObject
            return collection["features"]!!.jsonArray.map {
                it.jsonObject["properties"]!!
                    .jsonObject["name"]!!
                    .jsonPrimitive.content
            }
        }
    }
}
