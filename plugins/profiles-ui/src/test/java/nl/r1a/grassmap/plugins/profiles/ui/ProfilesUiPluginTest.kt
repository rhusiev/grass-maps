package nl.r1a.grassmap.plugins.profiles.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.ProfileAction
import nl.r1a.grassmap.plugin.api.model.ProfileActionKind
import nl.r1a.grassmap.plugin.api.model.ProfileSummary
import nl.r1a.grassmap.plugin.api.model.ProfilesState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The profiles plugin (T-0602): registration, the state stream and every command's effect. */
class ProfilesUiPluginTest {
    private val gravel =
        ProfilesState(
            profiles = listOf(ProfileSummary("bike", builtIn = true), ProfileSummary("gravel", "Gravel", copiedFrom = "bike")),
            groups = listOf("routing", "alerts"),
            undo = ProfileAction(ProfileActionKind.CREATED, "gravel", "bike"),
        )

    @Test
    fun activationDeclaresFourCommandsAndOneStateStream() =
        runBlocking {
            val context = activated(FakeProfileAccess(Outcome.Success(gravel)))

            assertEquals(ProfilesUiContract.commands.toSet(), context.commands.map { it.id }.toSet())
            val stream = context.streams.single()
            assertEquals(ProfilesUiContract.stateStream, stream.id)
            assertEquals(ProfilesUiContract.STATE_VALUE_TYPE, stream.valueType)
            assertTrue(context.providers.isEmpty())
            assertTrue(context.widgets.isEmpty())
            assertEquals(gravel, context.stateFlow().first())
        }

    @Test
    fun aHostWithoutProfileStoresStillActivatesWithAnEmptyList() =
        runBlocking {
            val context = activated(FakeProfileAccess(Outcome.Failure(AppError.NotReady)))

            assertEquals(ProfilesState(), context.stateFlow().first())
        }

    @Test
    fun createPassesTheNameAndTheSourceAndPublishesTheAnswer() =
        runBlocking {
            val access = FakeProfileAccess(Outcome.Success(gravel))
            val context = activated(access)

            val state = run(context, ProfilesUiContract.create, json("name" to "Gravel", "from" to "bike"))

            assertEquals(gravel, state)
            assertEquals("create:Gravel:bike", access.calls.last())
        }

    @Test
    fun copySettingsPassesTheGroupsWhenGiven() =
        runBlocking {
            val access = FakeProfileAccess()
            val context = activated(access)
            val params =
                buildJsonObject {
                    put("from", "walk")
                    put("to", "hike")
                    put("groups", buildJsonArray { add("routing") })
                }

            run(context, ProfilesUiContract.copySettings, params)
            run(context, ProfilesUiContract.copySettings, json("from" to "walk", "to" to "hike"))

            assertEquals(listOf("copy:walk:hike:[routing]", "copy:walk:hike:[]"), access.calls.takeLast(2))
        }

    @Test
    fun resetAndUndoReachTheHost() =
        runBlocking {
            val access = FakeProfileAccess()
            val context = activated(access)

            run(context, ProfilesUiContract.reset, json("profile" to "bike"))
            run(context, ProfilesUiContract.undo, JsonObject(emptyMap()))

            assertEquals(listOf("reset:bike:[]", "undo"), access.calls.takeLast(2))
        }

    @Test
    fun missingOrMalformedParametersAreInvalidInputAndNeverReachTheHost() =
        runBlocking {
            val access = FakeProfileAccess()
            val context = activated(access)
            val before = access.calls.size

            val bad =
                listOf(
                    ProfilesUiContract.create to json("name" to "Gravel"),
                    ProfilesUiContract.create to json("name" to " ", "from" to "bike"),
                    ProfilesUiContract.copySettings to json("from" to "walk"),
                    ProfilesUiContract.reset to JsonObject(emptyMap()),
                    ProfilesUiContract.reset to json("profile" to "bike", "groups" to "routing"),
                )

            for ((id, params) in bad) {
                assertEquals("$id $params", Outcome.Failure(AppError.InvalidInput), outcome(context, id, params))
            }
            assertEquals(before, access.calls.size)
        }

    @Test
    fun aHostFailureIsReturnedAndKeepsTheLastState() =
        runBlocking {
            val access = FakeProfileAccess(Outcome.Success(gravel))
            val context = activated(access)
            access.answer = FakeProfileAccess.NOT_FOUND

            val result = outcome(context, ProfilesUiContract.undo, JsonObject(emptyMap()))

            assertEquals(Outcome.Failure(AppError.NotFound), result)
            assertEquals(gravel, context.stateFlow().first())
        }

    private fun json(vararg pairs: Pair<String, String>): JsonObject = JsonObject(pairs.associate { (key, value) -> key to JsonPrimitive(value) })

    private suspend fun activated(access: FakeProfileAccess): RecordingContext {
        val context = RecordingContext(FakeServices(access))
        assertEquals(Outcome.Success(Unit), ProfilesUiPlugin().activate(context))
        return context
    }

    private suspend fun outcome(
        context: RecordingContext,
        id: CommandId,
        params: JsonElement,
    ): Outcome<CommandResult<JsonElement>> {
        return context.commands.single { it.id == id }.invokeRaw(params)
    }

    private suspend fun run(
        context: RecordingContext,
        id: CommandId,
        params: JsonElement,
    ): ProfilesState =
        when (val result = outcome(context, id, params)) {
            is Outcome.Success -> Json.decodeFromJsonElement(ProfilesState.serializer(), result.value.value)
            is Outcome.Failure -> throw AssertionError("command failed: ${result.error}")
        }

    @Suppress("UNCHECKED_CAST")
    private fun RecordingContext.stateFlow(): Flow<ProfilesState> = streams.single().flow as Flow<ProfilesState>
}
