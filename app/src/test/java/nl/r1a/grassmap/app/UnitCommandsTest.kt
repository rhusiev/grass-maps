package nl.r1a.grassmap.app

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.core.services.UnitSettings
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitCommandsTest {
    private val settings = UnitSettings()

    private val command: Command<*, *> = UnitsCommand({ settings })

    @Test
    fun theCommandStoresImperialAndReturnsIt() =
        runBlocking {
            val outcome = command.invokeRaw(JsonPrimitive("imperial"))

            assertEquals(Outcome.Success(CommandResult(JsonPrimitive("imperial"))), outcome)
            assertEquals(UnitSystem.Imperial, settings.units.value)
        }

    @Test
    fun anObjectParameterNamesTheUnits() =
        runBlocking {
            val params = buildJsonObject { put("units", "metric") }
            settings.set(UnitSystem.Imperial)

            val outcome = command.invokeRaw(params)

            assertEquals(Outcome.Success(CommandResult(JsonPrimitive("metric"))), outcome)
            assertEquals(UnitSystem.Metric, settings.units.value)
        }

    @Test
    fun anUnknownUnitIsInvalidInputAndChangesNothing() =
        runBlocking {
            val outcome = command.invokeRaw(JsonPrimitive("furlongs"))

            assertEquals(Outcome.Failure(AppError.InvalidInput), outcome)
            assertEquals(UnitSystem.Metric, settings.units.value)
        }

    @Test
    fun theSettingIsOnTheControlAllowlistAndRunsOnIo() {
        assertTrue(command.policy.allowlistedForControl)
        assertEquals(CommandDispatcher.IO, command.policy.dispatcher)
        assertEquals(UNIT_SETTINGS_COMMAND, command.id)
    }
}
