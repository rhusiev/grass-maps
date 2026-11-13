package nl.r1a.grassmap.plugins.route.planner

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.RouteHistoryAccess
import nl.r1a.grassmap.plugin.api.host.RoutingEngineAccess
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RouteHistoryEntry
import nl.r1a.grassmap.plugin.api.model.RoutePlanFailure
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmap.plugin.api.provider.RouteOptions
import nl.r1a.grassmap.plugin.api.provider.RoutePreference
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The route planner plugin (T-0302): registration, commands and the stream. */
class RoutePlannerPluginTest {
    @Test
    fun activationDeclaresTheCommandsAndBothStreams() =
        runTest {
            val context = context()
            val plugin = RoutePlannerPlugin()

            assertEquals(Outcome.Success(Unit), plugin.activate(context))

            assertEquals(RoutePlannerContract.commands.toSet(), context.commands.map { it.id }.toSet())
            val state = context.streams.first { stream -> stream.id == RoutePlannerContract.stateStream }
            assertEquals(RoutePlannerContract.STATE_VALUE_TYPE, state.valueType)
            assertEquals(RoutePlannerContract.STATE_VALUE_TYPE, RoutePlanState.serializer().descriptor.serialName)
            val history = context.streams.first { stream -> stream.id == RoutePlannerContract.historyStream }
            assertEquals(RoutePlannerContract.HISTORY_VALUE_TYPE, history.valueType)
            assertEquals(2, context.streams.size)
            assertTrue(context.providers.isEmpty())
            assertTrue(context.widgets.isEmpty())
        }

    @Test
    fun fiveCommandsUndoAndRedoThroughThePlugin() =
        runTest {
            val context = context()
            RoutePlannerPlugin().activate(context)
            context.command(RoutePlannerContract.begin).invokeRaw(JsonObject(emptyMap()))
            val edits = mutableListOf<RoutePlanState>()
            edits += invoke(context, RoutePlannerContract.setPoint, point("start", 49.8290, 23.9520))
            edits += invoke(context, RoutePlannerContract.setPoint, point("destination", 49.8420, 24.0320))
            edits += invoke(context, RoutePlannerContract.addVia, via("Cafe", 49.8330, 23.9700))
            edits += invoke(context, RoutePlannerContract.addVia, via("Museum", 49.8370, 23.9900))
            edits += invoke(context, RoutePlannerContract.moveVia, JsonObject(mapOf("from" to JsonPrimitive(0), "to" to JsonPrimitive(1))))

            val undone = mutableListOf<RoutePlanState>()
            repeat(edits.size) { undone += invoke(context, RoutePlannerContract.undo, JsonObject(emptyMap())) }
            val redone = mutableListOf<RoutePlanState>()
            repeat(edits.size) { redone += invoke(context, RoutePlannerContract.redo, JsonObject(emptyMap())) }

            assertEquals(
                edits.map { state -> state.copy(undoAvailable = false, redoAvailable = false) },
                redone.map { state -> state.copy(undoAvailable = false, redoAvailable = false) },
            )
            assertEquals(RoutePlanStatus.PickingStart, undone.last().status)
            assertTrue(undone.last().vias.isEmpty())
            assertFalse(undone.last().undoAvailable)
        }

    @Test
    fun aProducedRouteIsPublishedInTheHistoryAndCanBeReused() =
        runTest {
            val history = MemoryHistory()
            val context = context(history = history)
            RoutePlannerPlugin().activate(context)
            context.command(RoutePlannerContract.begin).invokeRaw(JsonObject(emptyMap()))
            context.command(RoutePlannerContract.setPoint).invokeRaw(point("start", 49.8290, 23.9520))
            context.command(RoutePlannerContract.setPoint).invokeRaw(point("destination", 49.8420, 24.0320))

            val stored = context.history().value
            assertEquals(1, stored.size)
            assertEquals(
                49.8290,
                stored
                    .single()
                    .start.position.latitude,
                0.0,
            )

            context.command(RoutePlannerContract.clear).invokeRaw(JsonObject(emptyMap()))
            context.command(RoutePlannerContract.begin).invokeRaw(JsonObject(emptyMap()))
            val reused = invoke(context, RoutePlannerContract.reuseHistory, JsonObject(mapOf("index" to JsonPrimitive(0))))

            assertEquals(RoutePlanStatus.Ready, reused.status)
            assertEquals(49.8290, reused.start?.position?.latitude ?: 0.0, 0.0)
            assertEquals(49.8420, reused.destination?.position?.latitude ?: 0.0, 0.0)
            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                context
                    .command(RoutePlannerContract.reuseHistory)
                    .invokeRaw(JsonObject(mapOf("index" to JsonPrimitive(9)))),
            )
        }

    @Test
    fun beginAsksForTheStartThenTheDestinationAndRoutes() =
        runTest {
            val engine = FakeEngine()
            val context = context(engine)
            RoutePlannerPlugin().activate(context)

            context.command(RoutePlannerContract.begin).invokeRaw(JsonObject(emptyMap()))
            assertEquals(RoutePlanStatus.PickingStart, context.state().value.status)

            context.command(RoutePlannerContract.setPoint).invokeRaw(point("start", 49.8290, 23.9520))
            assertEquals(RoutePlanStatus.PickingDestination, context.state().value.status)
            assertEquals(
                "start",
                context
                    .state()
                    .value.start
                    ?.label,
            )
            assertTrue(engine.requests.isEmpty())

            val routed =
                context
                    .command(RoutePlannerContract.setPoint)
                    .invokeRaw(point("destination", 49.8420, 24.0320))

            assertTrue(routed is Outcome.Success)
            val state = context.state().value
            assertEquals(RoutePlanStatus.Ready, state.status)
            assertEquals(2, state.options.size)
            assertEquals(1, engine.requests.single().alternatives)
            assertEquals(0, state.selectedIndex)
        }

    @Test
    fun swapRoutesAgainAndTheLineIsTheEnginesReverseAnswer() =
        runTest {
            val engine = FakeEngine()
            val context = context(engine)
            RoutePlannerPlugin().activate(context)
            context.command(RoutePlannerContract.begin).invokeRaw(JsonObject(emptyMap()))
            context.command(RoutePlannerContract.setPoint).invokeRaw(point("start", 49.8290, 23.9520))
            context.command(RoutePlannerContract.setPoint).invokeRaw(point("destination", 49.8420, 24.0320))
            val forward = context.state().value.selected ?: error("no forward route")

            val swapped = context.command(RoutePlannerContract.swap).invokeRaw(JsonObject(emptyMap()))

            assertTrue(swapped is Outcome.Success)
            assertEquals(2, engine.requests.size)
            assertEquals(
                listOf(FakeEngine.FORWARD_END, FakeEngine.FORWARD_START),
                engine.requests[1].waypoints,
            )
            val state = context.state().value
            assertEquals(FakeEngine.FORWARD_END, state.start?.position)
            assertEquals(FakeEngine.FORWARD_START, state.destination?.position)
            val reverse = state.selected ?: error("no reverse route")
            assertEquals(FakeEngine.REVERSE.first().geometry, reverse.geometry)
            assertNotEquals(forward.geometry, reverse.geometry)
            assertEquals(FakeEngine.REVERSE.first().distanceMeters, reverse.distanceMeters, 0.0)
        }

    @Test
    fun selectingAnAlternativeChangesTheSelectedLine() =
        runTest {
            val context = context()
            RoutePlannerPlugin().activate(context)
            context.command(RoutePlannerContract.begin).invokeRaw(JsonObject(emptyMap()))
            context.command(RoutePlannerContract.setPoint).invokeRaw(point("start", 49.8290, 23.9520))
            context.command(RoutePlannerContract.setPoint).invokeRaw(point("destination", 49.8420, 24.0320))
            val primary = context.state().value.selected ?: error("no primary route")

            val selected =
                context
                    .command(RoutePlannerContract.select)
                    .invokeRaw(JsonObject(mapOf("index" to JsonPrimitive(1))))

            assertTrue(selected is Outcome.Success)
            assertEquals(1, context.state().value.selectedIndex)
            val alternative = context.state().value.selected ?: error("no alternative route")
            assertNotEquals(primary.geometry, alternative.geometry)
            assertEquals(FakeEngine.FORWARD[1].distanceMeters, alternative.distanceMeters, 0.0)
        }

    @Test
    fun anEngineWithNoAlternativesReceivesZeroAndPublishesOneOption() =
        runTest {
            val engine = FakeEngine(maxAlternatives = 0)
            val context = context(engine)
            RoutePlannerPlugin().activate(context)
            context.command(RoutePlannerContract.begin).invokeRaw(JsonObject(emptyMap()))
            context.command(RoutePlannerContract.setPoint).invokeRaw(point("start", 49.8290, 23.9520))
            context.command(RoutePlannerContract.setPoint).invokeRaw(point("destination", 49.8420, 24.0320))

            assertEquals(0, engine.requests.single().alternatives)
            assertEquals(
                1,
                context
                    .state()
                    .value.options.size,
            )
            assertEquals(
                FakeEngine.FORWARD[0].distanceMeters,
                context
                    .state()
                    .value.selected
                    ?.distanceMeters ?: -1.0,
                0.0,
            )
        }

    @Test
    fun aMissingEngineLeavesATypedFailureState() =
        runTest {
            val context = context(engine = FakeEngine(), access = { Outcome.Failure(AppError.NotFound) })
            RoutePlannerPlugin().activate(context)
            context.command(RoutePlannerContract.begin).invokeRaw(JsonObject(emptyMap()))
            context.command(RoutePlannerContract.setPoint).invokeRaw(point("start", 49.8290, 23.9520))

            val routed =
                context
                    .command(RoutePlannerContract.setPoint)
                    .invokeRaw(point("destination", 49.8420, 24.0320))

            assertEquals(Outcome.Failure(AppError.NotFound), routed)
            assertEquals(RoutePlanStatus.Failed, context.state().value.status)
            assertEquals(RoutePlanFailure.EngineUnavailable, context.state().value.failure)
        }

    @Test
    fun aMissingPackReachesThePublishedStateAsItsOwnReason() =
        runTest {
            val missing =
                AppError.RoutingPackUnavailable(
                    AppError.RoutingPackUnavailable.Reason.Incomplete,
                    "/packs/valhalla has no tiles.tar",
                )
            val context = context(access = { Outcome.Failure(missing) })
            RoutePlannerPlugin().activate(context)
            context.command(RoutePlannerContract.begin).invokeRaw(JsonObject(emptyMap()))
            context.command(RoutePlannerContract.setPoint).invokeRaw(point("start", 49.8290, 23.9520))

            val routed =
                context
                    .command(RoutePlannerContract.setPoint)
                    .invokeRaw(point("destination", 49.8420, 24.0320))

            assertEquals(Outcome.Failure(missing), routed)
            assertEquals(RoutePlanStatus.Failed, context.state().value.status)
            assertEquals(RoutePlanFailure.PackMissing, context.state().value.failure)
        }

    @Test
    fun startIsThePreviewActionAndGuidanceIsNotWiredYet() =
        runTest {
            val context = context()
            RoutePlannerPlugin().activate(context)
            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                context.command(RoutePlannerContract.start).invokeRaw(JsonObject(emptyMap())),
            )

            context.command(RoutePlannerContract.begin).invokeRaw(JsonObject(emptyMap()))
            context.command(RoutePlannerContract.setPoint).invokeRaw(point("start", 49.8290, 23.9520))
            context.command(RoutePlannerContract.setPoint).invokeRaw(point("destination", 49.8420, 24.0320))

            assertEquals(
                Outcome.Failure(AppError.Unsupported),
                context.command(RoutePlannerContract.start).invokeRaw(JsonObject(emptyMap())),
            )
        }

    @Test
    fun clearReturnsThePlannerToIdle() =
        runTest {
            val context = context()
            RoutePlannerPlugin().activate(context)
            context.command(RoutePlannerContract.begin).invokeRaw(JsonObject(emptyMap()))
            context.command(RoutePlannerContract.setPoint).invokeRaw(point("start", 49.8290, 23.9520))
            context.command(RoutePlannerContract.setPoint).invokeRaw(point("destination", 49.8420, 24.0320))

            context.command(RoutePlannerContract.clear).invokeRaw(JsonObject(emptyMap()))

            assertEquals(RoutePlanStatus.Idle, context.state().value.status)
            assertTrue(
                context
                    .state()
                    .value.options
                    .isEmpty(),
            )
        }

    @Test
    fun aViaChainGoesOutAsOneOrderedRequestAndKeepsItsNames() =
        runTest {
            val engine = FakeEngine()
            val context = context(engine)
            RoutePlannerPlugin().activate(context)
            context.command(RoutePlannerContract.begin).invokeRaw(JsonObject(emptyMap()))
            context.command(RoutePlannerContract.setPoint).invokeRaw(point("start", 49.8290, 23.9520))
            context.command(RoutePlannerContract.addVia).invokeRaw(via("Cafe", 49.8330, 23.9700))
            context.command(RoutePlannerContract.addVia).invokeRaw(via("Museum", 49.8370, 23.9900))

            val routed =
                context
                    .command(RoutePlannerContract.setPoint)
                    .invokeRaw(point("destination", 49.8420, 24.0320))

            assertTrue(routed is Outcome.Success)
            val request = engine.requests.last()
            assertEquals(
                listOf(
                    GeoPoint(49.8290, 23.9520),
                    GeoPoint(49.8330, 23.9700),
                    GeoPoint(49.8370, 23.9900),
                    GeoPoint(49.8420, 24.0320),
                ),
                request.waypoints,
            )
            assertEquals(
                listOf("Cafe", "Museum"),
                context
                    .state()
                    .value.vias
                    .map { stop -> stop.label },
            )
            assertEquals(RoutePlanStatus.Ready, context.state().value.status)
        }

    @Test
    fun movingAndRemovingAViaRoutesTheNewChainAndKeepsTheLabelsInOrder() =
        runTest {
            val engine = FakeEngine()
            val context = context(engine)
            RoutePlannerPlugin().activate(context)
            context.command(RoutePlannerContract.begin).invokeRaw(JsonObject(emptyMap()))
            context.command(RoutePlannerContract.setPoint).invokeRaw(point("start", 49.8290, 23.9520))
            context.command(RoutePlannerContract.addVia).invokeRaw(via("Cafe", 49.8330, 23.9700))
            context.command(RoutePlannerContract.addVia).invokeRaw(via("Museum", 49.8370, 23.9900))
            context.command(RoutePlannerContract.setPoint).invokeRaw(point("destination", 49.8420, 24.0320))

            context.command(RoutePlannerContract.moveVia).invokeRaw(JsonObject(mapOf("from" to JsonPrimitive(0), "to" to JsonPrimitive(1))))

            assertEquals(
                listOf("Museum", "Cafe"),
                context
                    .state()
                    .value.vias
                    .map { stop -> stop.label },
            )
            assertEquals(
                listOf(
                    GeoPoint(49.8290, 23.9520),
                    GeoPoint(49.8370, 23.9900),
                    GeoPoint(49.8330, 23.9700),
                    GeoPoint(49.8420, 24.0320),
                ),
                engine.requests.last().waypoints,
            )

            context.command(RoutePlannerContract.removeVia).invokeRaw(JsonObject(mapOf("index" to JsonPrimitive(0))))

            assertEquals(
                listOf("Cafe"),
                context
                    .state()
                    .value.vias
                    .map { stop -> stop.label },
            )
            assertEquals(
                3,
                engine.requests
                    .last()
                    .waypoints.size,
            )
            assertEquals(RoutePlanStatus.Ready, context.state().value.status)
        }

    @Test
    fun aViaCommandWithoutCoordinatesOrWithABadIndexIsInvalidInput() =
        runTest {
            val context = context()
            RoutePlannerPlugin().activate(context)
            context.command(RoutePlannerContract.begin).invokeRaw(JsonObject(emptyMap()))

            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                context.command(RoutePlannerContract.addVia).invokeRaw(JsonObject(mapOf("lat" to JsonPrimitive(49.8330)))),
            )
            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                context.command(RoutePlannerContract.removeVia).invokeRaw(JsonObject(mapOf("index" to JsonPrimitive(0)))),
            )
            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                context
                    .command(RoutePlannerContract.moveVia)
                    .invokeRaw(JsonObject(mapOf("from" to JsonPrimitive(0), "to" to JsonPrimitive(1)))),
            )
        }

    @Test
    fun clearDropsTheViasAsWellAsTheEnds() =
        runTest {
            val context = context()
            RoutePlannerPlugin().activate(context)
            context.command(RoutePlannerContract.begin).invokeRaw(JsonObject(emptyMap()))
            context.command(RoutePlannerContract.setPoint).invokeRaw(point("start", 49.8290, 23.9520))
            context.command(RoutePlannerContract.addVia).invokeRaw(via("Cafe", 49.8330, 23.9700))
            context.command(RoutePlannerContract.setPoint).invokeRaw(point("destination", 49.8420, 24.0320))

            context.command(RoutePlannerContract.clear).invokeRaw(JsonObject(emptyMap()))

            assertTrue(
                context
                    .state()
                    .value.vias
                    .isEmpty(),
            )
            assertEquals(RoutePlanStatus.Idle, context.state().value.status)
        }

    @Test
    fun thePreferenceCommandsApplyAndResetOneRoute() =
        runTest {
            val engine = FakeEngine()
            val profileDefaults = RouteOptions(hills = RoutePreference.Avoid)
            val context = context(engine, options = profileDefaults)
            RoutePlannerPlugin().activate(context)
            context.command(RoutePlannerContract.begin).invokeRaw(JsonObject(emptyMap()))
            context.command(RoutePlannerContract.setPoint).invokeRaw(point("start", 49.8290, 23.9520))
            context.command(RoutePlannerContract.setPoint).invokeRaw(point("destination", 49.8420, 24.0320))
            assertEquals(profileDefaults, engine.requests.last().options)

            val override =
                RouteOptions(
                    hills = RoutePreference.DontMind,
                    busyRoads = RoutePreference.Avoid,
                    roughSurfaces = RoutePreference.Normal,
                )
            val applied =
                invoke(
                    context,
                    RoutePlannerContract.setPreferences,
                    buildJsonObject {
                        put("hills", "dont_mind")
                        put("busy_roads", "avoid")
                        put("rough_surfaces", "normal")
                    },
                )

            assertEquals(override, engine.requests.last().options)
            assertEquals(profileDefaults, applied.profilePreferences)
            assertEquals(override, applied.overridePreferences)
            assertEquals(override, applied.preferences)
            assertEquals(RoutePlanStatus.Ready, applied.status)

            val reset = invoke(context, RoutePlannerContract.resetPreferences, JsonObject(emptyMap()))

            assertEquals(profileDefaults, engine.requests.last().options)
            assertNull(reset.overridePreferences)
            assertEquals(profileDefaults, reset.preferences)
        }

    @Test
    fun aPreferenceCommandWithAnUnknownOrMissingStepIsInvalidInput() =
        runTest {
            val engine = FakeEngine()
            val context = context(engine)
            RoutePlannerPlugin().activate(context)
            context.command(RoutePlannerContract.begin).invokeRaw(JsonObject(emptyMap()))
            context.command(RoutePlannerContract.setPoint).invokeRaw(point("start", 49.8290, 23.9520))
            context.command(RoutePlannerContract.setPoint).invokeRaw(point("destination", 49.8420, 24.0320))
            val before = engine.requests.size

            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                context
                    .command(RoutePlannerContract.setPreferences)
                    .invokeRaw(
                        buildJsonObject {
                            put("hills", "sometimes")
                            put("busy_roads", "normal")
                            put("rough_surfaces", "normal")
                        },
                    ),
            )
            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                context
                    .command(RoutePlannerContract.setPreferences)
                    .invokeRaw(buildJsonObject { put("hills", "avoid") }),
            )
            assertEquals(before, engine.requests.size)
            assertNull(context.state().value.overridePreferences)
        }

    private suspend fun invoke(
        context: RecordingContext,
        id: CommandId,
        params: JsonObject,
    ): RoutePlanState {
        val outcome = context.command(id).invokeRaw(params)
        if (outcome is Outcome.Failure) {
            error("$id failed: ${outcome.error}")
        }
        return context.state().value
    }

    private fun context(
        engine: FakeEngine = FakeEngine(),
        access: RoutingEngineAccess = RoutingEngineAccess { Outcome.Success(engine) },
        history: RouteHistoryAccess = MemoryHistory(),
        options: RouteOptions = RouteOptions(),
    ): RecordingContext =
        RecordingContext(
            FakeServices(
                routingEngines = access,
                routeHistory = history,
                routingOptions = { profile -> if (profile == RouteProfile.Walk) options else RouteOptions() },
            ),
        )

    private class MemoryHistory : RouteHistoryAccess {
        private val entries = mutableListOf<RouteHistoryEntry>()

        override suspend fun remember(entry: RouteHistoryEntry): Outcome<List<RouteHistoryEntry>> {
            entries.removeAll { existing -> existing.stops == entry.stops }
            entries.add(0, entry.copy(atMillis = entries.size + 1L))
            return Outcome.Success(entries.toList())
        }

        override suspend fun recent(): Outcome<List<RouteHistoryEntry>> = Outcome.Success(entries.toList())

        override suspend fun clear(): Outcome<Unit> {
            entries.clear()
            return Outcome.Success(Unit)
        }
    }

    private fun point(
        role: String,
        lat: Double,
        lon: Double,
    ): JsonObject =
        buildJsonObject {
            put("role", role)
            put("lat", lat)
            put("lon", lon)
            put("label", role)
        }

    private fun via(
        label: String,
        lat: Double,
        lon: Double,
    ): JsonObject =
        buildJsonObject {
            put("label", label)
            put("lat", lat)
            put("lon", lon)
        }
}
