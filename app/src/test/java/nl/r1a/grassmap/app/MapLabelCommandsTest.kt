package nl.r1a.grassmap.app

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.map.LabelLanguage
import nl.r1a.grassmap.map.LabelOptions
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLabelCommandsTest {
    private val settings = MapLabelSettings()

    private val command: Command<*, *> = MapLabelsCommand({ settings })

    @Test
    fun theCommandStoresLanguageAndSize() =
        runBlocking {
            val params =
                buildJsonObject {
                    put("language", "en")
                    put("sizePercent", 150)
                }

            val outcome = command.invokeRaw(params)

            val stored = """{"language":"en","sizePercent":150}"""
            assertEquals(Outcome.Success(CommandResult(JsonPrimitive(stored))), outcome)
            assertEquals(LabelOptions(LabelLanguage.English, 150), settings.options.value)
        }

    @Test
    fun aMissingKeyKeepsItsCurrentValue() =
        runBlocking {
            settings.set(LabelOptions(LabelLanguage.Ukrainian, 125))

            command.invokeRaw(buildJsonObject { put("sizePercent", 175) })

            assertEquals(LabelOptions(LabelLanguage.Ukrainian, 175), settings.options.value)
        }

    @Test
    fun anUnknownLanguageOrASizeOutOfRangeIsInvalidInputAndChangesNothing() =
        runBlocking {
            val badLanguage = command.invokeRaw(buildJsonObject { put("language", "fr") })
            val badSize = command.invokeRaw(buildJsonObject { put("sizePercent", 10) })
            val bare = command.invokeRaw(JsonPrimitive("en"))

            assertEquals(Outcome.Failure(AppError.InvalidInput), badLanguage)
            assertEquals(Outcome.Failure(AppError.InvalidInput), badSize)
            assertEquals(Outcome.Failure(AppError.InvalidInput), bare)
            assertEquals(LabelOptions(), settings.options.value)
        }

    @Test
    fun theSettingIsOnTheControlAllowlistAndRunsOnIo() {
        assertTrue(command.policy.allowlistedForControl)
        assertEquals(CommandDispatcher.IO, command.policy.dispatcher)
        assertEquals(MAP_LABEL_SETTINGS_COMMAND, command.id)
    }
}
