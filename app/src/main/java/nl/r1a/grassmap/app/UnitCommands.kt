package nl.r1a.grassmap.app

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nl.r1a.grassmap.core.services.UnitSettings
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.UnitSystem

/** The control-channel trigger for the length unit setting (T-0107). */
internal val UNIT_SETTINGS_COMMAND: CommandId = CommandId("core.settings.units")

/**
 * Sets the length unit system and returns its wire name.
 *
 * The settings screen arrives later; until then this command is how the control
 * channel proves the setting on a device. It takes `{"units":"imperial"}` or
 * the bare string, and it runs on IO because creating [UnitSettings] may read
 * the settings file.
 */
internal class UnitsCommand(
    private val settings: () -> UnitSettings,
    private val log: (String) -> Unit = {},
) : Command<JsonElement, String>(
        id = UNIT_SETTINGS_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Sets the length units to metric or imperial and returns the active one.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val requested = parse(params) ?: return Outcome.Failure(AppError.InvalidInput)
        return when (val outcome = settings().set(requested)) {
            is Outcome.Success -> {
                val name = wireName(requested)
                log("units set to $name")
                Outcome.Success(CommandResult(name))
            }

            is Outcome.Failure -> {
                outcome
            }
        }
    }

    private fun parse(params: JsonElement): UnitSystem? =
        when (params) {
            is JsonPrimitive -> unitSystem(params.contentOrNull)
            is JsonObject -> unitSystem((params[UNITS_KEY] as? JsonPrimitive)?.contentOrNull)
            else -> null
        }

    private fun unitSystem(name: String?): UnitSystem? =
        when (name) {
            "metric" -> UnitSystem.Metric
            "imperial" -> UnitSystem.Imperial
            else -> null
        }

    private fun wireName(units: UnitSystem): String =
        when (units) {
            UnitSystem.Metric -> "metric"
            UnitSystem.Imperial -> "imperial"
        }

    private companion object {
        const val UNITS_KEY = "units"
    }
}
