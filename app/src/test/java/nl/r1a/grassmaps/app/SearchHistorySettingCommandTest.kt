package nl.r1a.grassmaps.app

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmaps.core.services.SearchHistorySettings
import nl.r1a.grassmaps.plugin.api.command.Command
import nl.r1a.grassmaps.plugin.api.command.CommandDispatcher
import nl.r1a.grassmaps.plugin.api.command.CommandResult
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHistorySettingCommandTest {
    private val settings = SearchHistorySettings()

    private val command: Command<*, *> = SearchHistorySettingCommand({ settings })

    @Test
    fun theCommandTurnsHistoryOffAndReturnsIt() =
        runBlocking {
            val outcome = command.invokeRaw(JsonPrimitive(false))

            assertEquals(Outcome.Success(CommandResult(JsonPrimitive("enabled=false"))), outcome)
            assertEquals(false, settings.isEnabled())
        }

    @Test
    fun anObjectParameterNamesTheValue() =
        runBlocking {
            val params = buildJsonObject { put("enabled", true) }
            settings.set(false)

            val outcome = command.invokeRaw(params)

            assertEquals(Outcome.Success(CommandResult(JsonPrimitive("enabled=true"))), outcome)
            assertEquals(true, settings.isEnabled())
        }

    @Test
    fun aNonBooleanValueIsInvalidInputAndChangesNothing() =
        runBlocking {
            val outcome = command.invokeRaw(buildJsonObject { put("enabled", "maybe") })

            assertEquals(Outcome.Failure(AppError.InvalidInput), outcome)
            assertEquals(true, settings.isEnabled())
        }

    @Test
    fun theSettingIsOnTheControlAllowlistAndRunsOnIo() {
        assertTrue(command.policy.allowlistedForControl)
        assertEquals(CommandDispatcher.IO, command.policy.dispatcher)
        assertEquals(SEARCH_HISTORY_SETTING_COMMAND, command.id)
    }
}
