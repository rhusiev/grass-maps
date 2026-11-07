package nl.r1a.grassmap.core.services

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome

/** The diagnostic commands the control channel can invoke. */
object DiagnosticsCommands {
    /** Writes one diagnostic bundle and returns its name and size. */
    val WRITE_BUNDLE: CommandId = CommandId("core.diagnostics.write_bundle")

    /** Turns diagnostic logging on or off; params `{"enabled":true}`. */
    val SET_LOGGING: CommandId = CommandId("core.diagnostics.set_logging")
}

/**
 * The commands over a [Diagnostics] instance. They are host commands with no UI
 * binding; the debug control channel invokes them. The diagnostics instance is
 * resolved when a command runs, on its IO dispatcher, so constructing it (which
 * reads the settings file) never runs on the Main thread.
 *
 * The bundle stays in app-private storage. The command returns the file name
 * and byte count, not a shareable URI, and no release path exposes sharing.
 */
fun diagnosticsCommands(diagnostics: () -> Diagnostics?): List<Command<*, *>> =
    listOf(
        WriteDiagnosticBundleCommand(diagnostics),
        SetDiagnosticLoggingCommand(diagnostics),
    )

internal class WriteDiagnosticBundleCommand(
    private val diagnostics: () -> Diagnostics?,
) : Command<JsonElement, String>(
        id = DiagnosticsCommands.WRITE_BUNDLE,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Writes a scrubbed diagnostic bundle with the log ring and device info.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val active = diagnostics() ?: return Outcome.Failure(AppError.NotReady)
        return when (val outcome = active.bundles.write()) {
            is Outcome.Failure -> {
                outcome
            }

            is Outcome.Success -> {
                Outcome.Success(
                    CommandResult("bundle=${outcome.value.name} bytes=${outcome.value.length()}"),
                )
            }
        }
    }
}

internal class SetDiagnosticLoggingCommand(
    private val diagnostics: () -> Diagnostics?,
) : Command<JsonElement, String>(
        id = DiagnosticsCommands.SET_LOGGING,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Turns diagnostic logging on or off.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val enabled =
            ((params as? JsonObject)?.get(ENABLED) as? JsonPrimitive)?.booleanOrNull
                ?: return Outcome.Failure(AppError.InvalidInput)
        val active = diagnostics() ?: return Outcome.Failure(AppError.NotReady)
        return when (val outcome = active.setLoggingEnabled(enabled)) {
            is Outcome.Failure -> outcome
            is Outcome.Success -> Outcome.Success(CommandResult("enabled=$enabled"))
        }
    }

    private companion object {
        const val ENABLED = "enabled"
    }
}
