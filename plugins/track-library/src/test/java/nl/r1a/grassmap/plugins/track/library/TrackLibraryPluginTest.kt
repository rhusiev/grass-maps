package nl.r1a.grassmap.plugins.track.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.command.CancellationKey
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.ElevationAccess
import nl.r1a.grassmap.plugin.api.host.PluginServices
import nl.r1a.grassmap.plugin.api.host.StreamAccess
import nl.r1a.grassmap.plugin.api.host.StreamDescriptor
import nl.r1a.grassmap.plugin.api.host.TrackLibraryAccess
import nl.r1a.grassmap.plugin.api.host.TrackLibraryChange
import nl.r1a.grassmap.plugin.api.host.TrackLibraryUndo
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.model.TrackFolderSummary
import nl.r1a.grassmap.plugin.api.model.TrackLibraryAction
import nl.r1a.grassmap.plugin.api.model.TrackLibraryEntry
import nl.r1a.grassmap.plugin.api.model.TrackLibraryState
import nl.r1a.grassmap.plugin.api.model.TrackSort
import nl.r1a.grassmap.plugin.api.provider.Provider
import nl.r1a.grassmap.plugin.api.service.Clock
import nl.r1a.grassmap.plugin.api.service.LocationService
import nl.r1a.grassmap.plugin.api.widget.WidgetDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The track library plugin (T-0414): registration, actions, view controls and undo. */
class TrackLibraryPluginTest {
    @Test
    fun activationDeclaresNineCommandsAndOneStateStream() =
        runTest {
            val context = RecordingContext(FakeServices(FakeLibraryAccess()))
            val plugin = TrackLibraryPlugin()

            assertEquals(Outcome.Success(Unit), plugin.activate(context))

            assertEquals(TrackLibraryContract.commands.toSet(), context.commands.map { it.id }.toSet())
            val stream = context.streams.single()
            assertEquals(TrackLibraryContract.stateStream, stream.id)
            assertEquals(TrackLibraryContract.STATE_VALUE_TYPE, stream.valueType)
            assertEquals(TrackLibraryContract.STATE_VALUE_TYPE, TrackLibraryState.serializer().descriptor.serialName)
            assertTrue(context.providers.isEmpty())
            assertTrue(context.widgets.isEmpty())
            assertTrue(context.commands.none { command -> command.policy.undoable })
            assertEquals(
                CancellationKey("track.library.query"),
                context.command(TrackLibraryContract.setQuery).policy.cancellableBy,
            )
        }

    @Test
    fun creatingAFolderPublishesThePersistedStateAndOffersUndo() =
        runTest {
            val access = FakeLibraryAccess()
            val context = context(access)

            context.command(TrackLibraryContract.createFolder).invokeRaw(params("name" to "Hikes"))

            assertEquals(listOf("create:Hikes"), access.calls)
            val state = context.state()
            assertEquals(listOf("All", "Other", "Hikes"), state.folders.map { folder -> folder.name })
            assertTrue(state.undoAvailable)
            assertEquals(TrackLibraryAction.FOLDER_CREATED, state.lastAction)
        }

    @Test
    fun undoingAFolderCreationRemovesItAndTheReceipt() =
        runTest {
            val context = context(FakeLibraryAccess())
            context.command(TrackLibraryContract.createFolder).invokeRaw(params("name" to "Hikes"))

            context.command(TrackLibraryContract.undo).invokeRaw(JsonObject(emptyMap()))

            val state = context.state()
            assertEquals(listOf("All", "Other"), state.folders.map { folder -> folder.name })
            assertEquals(false, state.undoAvailable)
        }

    @Test
    fun editingRenamesDescribesAndFilesTheTrackInOneAction() =
        runTest {
            val access = FakeLibraryAccess()
            val context = context(access)

            context.command(TrackLibraryContract.editTrack).invokeRaw(
                params(
                    "trackId" to "t1",
                    "name" to "Ridges",
                    "description" to "<b>Note</b>",
                    "folderId" to "f1",
                ),
            )

            assertEquals(listOf("edit:t1:Ridges:<b>Note</b>:f1"), access.calls)
            val row =
                context
                    .state()
                    .browse.rows
                    .first { row -> row.id == "t1" }
            assertEquals("Ridges", row.name)
            assertEquals("f1", row.listId)
        }

    @Test
    fun movingATrackWithAnEmptyFolderUnfilesIt() =
        runTest {
            val access = FakeLibraryAccess()
            val context = context(access)

            context.command(TrackLibraryContract.moveTrack).invokeRaw(params("trackId" to "t1", "folderId" to ""))

            assertEquals(listOf("move:t1:null"), access.calls)
            assertNull(
                context
                    .state()
                    .tracks
                    .first { track -> track.id == "t1" }
                    .listId,
            )
        }

    @Test
    fun deletingATrackAndUndoingRestoresItToItsFolder() =
        runTest {
            val access = FakeLibraryAccess()
            val context = context(access)

            context.command(TrackLibraryContract.deleteTrack).invokeRaw(params("trackId" to "t1"))
            assertEquals(1, context.state().tracks.size)

            context.command(TrackLibraryContract.undo).invokeRaw(JsonObject(emptyMap()))

            val restored = context.state().tracks.first { track -> track.id == "t1" }
            assertEquals("t1", restored.id)
            assertEquals("f1", restored.listId)
        }

    @Test
    fun anUndoWithNoReceiptIsATypedNoOp() =
        runTest {
            val context = context(FakeLibraryAccess())

            val outcome = context.command(TrackLibraryContract.undo).invokeRaw(JsonObject(emptyMap()))

            assertTrue(outcome is Outcome.Success)
            assertEquals(false, context.state().undoAvailable)
        }

    @Test
    fun aSearchKeystrokeOrdersItselfAndAFolderFiltersTheRows() =
        runTest {
            val context = context(FakeLibraryAccess())

            context.command(TrackLibraryContract.setQuery).invokeRaw(params("query" to "rid", "sequence" to "2"))
            assertEquals(
                listOf("t1"),
                context
                    .state()
                    .browse.rows
                    .map { row -> row.id },
            )

            context.command(TrackLibraryContract.setQuery).invokeRaw(params("query" to "nothing", "sequence" to "1"))
            assertEquals(
                listOf("t1"),
                context
                    .state()
                    .browse.rows
                    .map { row -> row.id },
            )

            context.command(TrackLibraryContract.setQuery).invokeRaw(params("query" to ""))
            context.command(TrackLibraryContract.selectFolder).invokeRaw(params("folderId" to "f1"))
            assertEquals(
                listOf("t1", "t2"),
                context
                    .state()
                    .browse.rows
                    .map { row -> row.id },
            )
        }

    @Test
    fun anUnknownFolderFallsBackToEveryTrack() =
        runTest {
            val context = context(FakeLibraryAccess())

            context.command(TrackLibraryContract.selectFolder).invokeRaw(params("folderId" to "missing"))

            assertNull(context.state().browse.selectedListId)
            assertEquals(
                2,
                context
                    .state()
                    .browse.rows.size,
            )
        }

    @Test
    fun nearestUsesTheOriginSentWithTheSort() {
        runTest {
            val context = context(FakeLibraryAccess())

            context.command(TrackLibraryContract.setSort).invokeRaw(
                params("sort" to "nearest", "latitude" to "49.80", "longitude" to "24.00"),
            )

            assertEquals(
                listOf("t2", "t1"),
                context
                    .state()
                    .browse.rows
                    .map { row -> row.id },
            )
            assertEquals(49.80, context.state().browse.originLatitude)
        }
    }

    @Test
    fun eachFixedSortChangesTheRows() =
        runTest {
            val context = context(FakeLibraryAccess())

            for ((wire, expected) in EXPECTED_SORTS) {
                context.command(TrackLibraryContract.setSort).invokeRaw(params("sort" to wire))
                assertEquals(
                    wire,
                    expected,
                    context
                        .state()
                        .browse.rows
                        .map { row -> row.id },
                )
            }
        }

    @Test
    fun anUnknownSortIsInvalidInput() =
        runTest {
            val context = context(FakeLibraryAccess())

            val outcome = context.command(TrackLibraryContract.setSort).invokeRaw(params("sort" to "oldest"))

            assertEquals(Outcome.Failure(AppError.InvalidInput), outcome)
        }

    private suspend fun context(access: TrackLibraryAccess = FakeLibraryAccess()): RecordingContext {
        val context = RecordingContext(FakeServices(access))
        TrackLibraryPlugin().activate(context)
        return context
    }

    private fun params(vararg pairs: Pair<String, String>): JsonObject =
        buildJsonObject {
            pairs.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
        }

    private fun RecordingContext.state(): TrackLibraryState =
        (streams.single().flow as kotlinx.coroutines.flow.StateFlow<TrackLibraryState>).value

    private fun RecordingContext.command(id: CommandId): Command<JsonElement, *> =
        commands.first { command -> command.id == id } as Command<JsonElement, *>

    private companion object {
        val EXPECTED_SORTS =
            mapOf(
                "newest" to listOf("t1", "t2"),
                "name" to listOf("t2", "t1"),
                "distance" to listOf("t2", "t1"),
            )
    }
}

internal class RecordingContext(
    override val services: PluginServices,
) : PluginContext {
    override val pluginId: PluginId = PluginId(TrackLibraryContract.PLUGIN_ID)

    val commands = mutableListOf<Command<*, *>>()
    val streams = mutableListOf<StreamDescriptor<*>>()
    val providers = mutableListOf<Provider>()
    val widgets = mutableListOf<WidgetDescriptor>()

    override fun addCommand(command: Command<*, *>) {
        commands += command
    }

    override fun addStream(stream: StreamDescriptor<*>) {
        streams += stream
    }

    override fun addProvider(provider: Provider) {
        providers += provider
    }

    override fun addWidget(widget: WidgetDescriptor) {
        widgets += widget
    }
}

internal class FakeServices(
    override val trackLibrary: TrackLibraryAccess,
) : PluginServices {
    override val streams: StreamAccess =
        object : StreamAccess {
            override fun <T : Any> subscribe(streamId: StreamId): Flow<T> = emptyFlow()
        }

    override val location: LocationService =
        object : LocationService {
            override val fixes: Flow<LocationFix?> = emptyFlow()

            override fun hasPermission(): Boolean = false
        }

    override val elevation: ElevationAccess = ElevationAccess { Outcome.Success(null) }

    override val camera: Flow<Camera> = emptyFlow()

    override val clock: Clock = Clock { 0L }
}

/**
 * A typed fake of the host access. It records every call, keeps the same
 * process-scoped undo stack the host has, and restores its own state, so the
 * plugin's published stream and its receipts are testable without a file.
 */
internal class FakeLibraryAccess : TrackLibraryAccess {
    val calls = mutableListOf<String>()
    private val undoStack = ArrayDeque<() -> Unit>()
    private var receipt = 0
    private var state =
        TrackLibraryState(
            folders =
                listOf(
                    TrackFolderSummary(id = "f1", name = "All", trackCount = 1),
                    TrackFolderSummary(id = "f2", name = "Other", trackCount = 1),
                ),
            tracks =
                listOf(
                    TrackLibraryEntry(
                        id = "t1",
                        name = "Ridges",
                        listId = "f1",
                        distanceMeters = 7_500.0,
                        ascentMeters = 219.0,
                        durationMillis = 9_660_000L,
                        savedAtMillis = 200L,
                        startLatitude = 49.84,
                        startLongitude = 24.03,
                    ),
                    TrackLibraryEntry(
                        id = "t2",
                        name = "Forest",
                        listId = "f1",
                        distanceMeters = 9_300.0,
                        ascentMeters = 180.0,
                        durationMillis = 11_000_000L,
                        savedAtMillis = 100L,
                        startLatitude = 49.80,
                        startLongitude = 24.00,
                    ),
                ),
        )

    override suspend fun state(): Outcome<TrackLibraryState> = Outcome.Success(state)

    override suspend fun createFolder(name: String): Outcome<TrackLibraryChange> {
        calls += "create:$name"
        val previous = state
        return change(TrackLibraryAction.FOLDER_CREATED) {
            state =
                state.copy(
                    folders = state.folders + TrackFolderSummary(id = "f${state.folders.size + 1}", name = name),
                )
        }
    }

    override suspend fun renameFolder(
        folderId: String,
        name: String,
    ): Outcome<TrackLibraryChange> {
        calls += "rename:$folderId:$name"
        return change(TrackLibraryAction.FOLDER_RENAMED) {
            state =
                state.copy(
                    folders = state.folders.map { folder -> if (folder.id == folderId) folder.copy(name = name) else folder },
                )
        }
    }

    override suspend fun editTrack(
        trackId: String,
        name: String?,
        description: String?,
        folderId: String?,
    ): Outcome<TrackLibraryChange> {
        calls += "edit:$trackId:$name:$description:$folderId"
        return change(TrackLibraryAction.TRACK_EDITED) {
            state =
                state.copy(
                    tracks =
                        state.tracks.map { track ->
                            if (track.id == trackId) {
                                track.copy(name = name, description = description, listId = folderId)
                            } else {
                                track
                            }
                        },
                )
        }
    }

    override suspend fun moveTrack(
        trackId: String,
        folderId: String?,
    ): Outcome<TrackLibraryChange> {
        calls += "move:$trackId:$folderId"
        return change(TrackLibraryAction.TRACK_MOVED) {
            state =
                state.copy(
                    tracks = state.tracks.map { track -> if (track.id == trackId) track.copy(listId = folderId) else track },
                )
        }
    }

    override suspend fun deleteTrack(trackId: String): Outcome<TrackLibraryChange> {
        calls += "delete:$trackId"
        return change(TrackLibraryAction.TRACK_DELETED) {
            state = state.copy(tracks = state.tracks.filterNot { track -> track.id == trackId })
        }
    }

    override suspend fun undo(receipt: String): Outcome<TrackLibraryUndo> {
        val action = undoStack.removeLastOrNull() ?: return Outcome.Success(TrackLibraryUndo.NOT_APPLICABLE)
        action()
        state = state.copy(lastAction = TrackLibraryAction.NONE)
        return Outcome.Success(TrackLibraryUndo.APPLIED)
    }

    private fun change(
        action: TrackLibraryAction,
        mutate: () -> Unit,
    ): Outcome<TrackLibraryChange> {
        val previous = state
        mutate()
        val changed = state.copy(lastAction = action)
        state = changed
        undoStack.addLast { state = previous }
        return Outcome.Success(TrackLibraryChange(state = changed, receipt = "r${++receipt}"))
    }
}
