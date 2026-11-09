package nl.r1a.grassmap.plugins.favourites.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.FavouriteListsState
import nl.r1a.grassmap.plugin.api.model.FavouriteSpecial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lists plugin (T-0213): registration, actions, state and scoped undo. */
class FavouritesUiPluginTest {
    @Test
    fun activationDeclaresSixCommandsAndOneListsStream() =
        runTest {
            val context = context()
            val plugin = FavouritesUiPlugin()

            assertEquals(Outcome.Success(Unit), plugin.activate(context))

            assertEquals(FavouritesUiContract.commands.toSet(), context.commands.map { it.id }.toSet())
            val stream = context.streams.single()
            assertEquals(FavouritesUiContract.listsStream, stream.id)
            assertEquals(FavouritesUiContract.LISTS_VALUE_TYPE, stream.valueType)
            assertEquals(FavouritesUiContract.LISTS_VALUE_TYPE, FavouriteListsState.serializer().descriptor.serialName)
            assertTrue(context.providers.isEmpty())
            assertTrue(context.widgets.isEmpty())
            assertTrue(context.commands.none { it.policy.undoable })
        }

    @Test
    fun creatingAListPublishesThePersistedState() =
        runTest {
            val access = FakeListsAccess()
            val context = context(access)
            FavouritesUiPlugin().activate(context)

            val outcome = context.command(FavouritesUiContract.createList).invokeRaw(listParams("name" to "Cafes", "color" to "#00FF00"))

            assertTrue(outcome is Outcome.Success)
            assertEquals(listOf("create:Cafes:#00FF00:null"), access.calls)
            assertEquals(
                listOf("Lviv", "Cafes"),
                context
                    .lists()
                    .first()
                    .lists
                    .map { it.name },
            )
        }

    @Test
    fun movingARecolouringAndSettingHomeAndWorkReachTheAccess() =
        runTest {
            val access = FakeListsAccess()
            val context = context(access)
            FavouritesUiPlugin().activate(context)

            context.command(FavouritesUiContract.movePoint).invokeRaw(listParams("pointId" to "p1", "list" to "Lviv"))
            context.command(FavouritesUiContract.recolourList).invokeRaw(listParams("list" to "Lviv", "color" to "#0000FF"))
            context.command(FavouritesUiContract.setHome).invokeRaw(listParams("pointId" to "p1"))
            context.command(FavouritesUiContract.setWork).invokeRaw(listParams("pointId" to "p1"))

            assertEquals(
                listOf("move:p1:Lviv", "recolour:Lviv:#0000FF", "special:p1:HOME", "special:p1:WORK"),
                access.calls,
            )
            val state = context.lists().first()
            assertEquals("#0000FF", state.lists.single().color)
            assertEquals(FavouriteSpecial.WORK, state.points.single().special)
            assertTrue(state.undoAvailable)
        }

    @Test
    fun undoConsumesTheLatestReceiptOnce() =
        runTest {
            val access = FakeListsAccess()
            val context = context(access)
            FavouritesUiPlugin().activate(context)
            context.command(FavouritesUiContract.createList).invokeRaw(listParams("name" to "Cafes", "color" to "#00FF00"))
            context.command(FavouritesUiContract.movePoint).invokeRaw(listParams("pointId" to "p1", "list" to "Cafes"))

            context.undo()

            assertEquals(listOf("create:Cafes:#00FF00:null", "move:p1:Cafes", "undo:r-move"), access.calls)
            assertEquals("r-move", access.lastUndoReceipt)
            context.undo()
            assertEquals("r-create", access.lastUndoReceipt)
        }

    @Test
    fun undoWithNoReceiptIsATypedNoOpThatDoesNotCallTheHost() =
        runTest {
            val access = FakeListsAccess()
            val context = context(access)
            FavouritesUiPlugin().activate(context)

            val outcome = context.command(FavouritesUiContract.undo).invokeRaw(JsonObject(emptyMap()))

            assertTrue(outcome is Outcome.Success)
            assertTrue(access.calls.isEmpty())
            assertNull(access.lastUndoReceipt)
        }

    @Test
    fun invalidParametersAreInvalidInputAndTouchNothing() =
        runTest {
            val access = FakeListsAccess()
            val context = context(access)
            FavouritesUiPlugin().activate(context)

            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                context.command(FavouritesUiContract.createList).invokeRaw(listParams("name" to "Cafes")),
            )
            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                context.command(FavouritesUiContract.movePoint).invokeRaw(listParams("pointId" to "p1")),
            )
            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                context.command(FavouritesUiContract.recolourList).invokeRaw(listParams("list" to "Lviv")),
            )
            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                context.command(FavouritesUiContract.setHome).invokeRaw(JsonObject(emptyMap())),
            )
            assertTrue(access.calls.isEmpty())
        }

    private fun context(access: FakeListsAccess = FakeListsAccess()): RecordingContext = RecordingContext(FakeServices(access))

    @Suppress("UNCHECKED_CAST")
    private fun RecordingContext.lists(): Flow<FavouriteListsState> =
        streams.single { it.id == FavouritesUiContract.listsStream }.flow as Flow<FavouriteListsState>

    private fun RecordingContext.command(id: CommandId): Command<*, *> = commands.single { it.id == id }

    private suspend fun RecordingContext.undo() {
        command(FavouritesUiContract.undo).invokeRaw(JsonObject(emptyMap()))
    }

    private fun listParams(vararg pairs: Pair<String, String>): JsonObject =
        buildJsonObject {
            pairs.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
        }
}
