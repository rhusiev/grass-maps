package nl.r1a.grassmap.plugin.api.command

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome

@JvmInline
value class CancellationKey(val value: String) {
    override fun toString(): String = value
}

/** The dispatcher group a command runs on. */
enum class CommandDispatcher {
    MAIN,
    DEFAULT,
    IO,
}

/**
 * Declared command behaviour. A policy is data: the host enforces it at dispatch.
 */
data class CommandPolicy(
    val cancellableBy: CancellationKey? = null,
    val undoable: Boolean = false,
    val requiresSwitch: SwitchId? = null,
    val allowlistedForControl: Boolean = false,
    val dispatcher: CommandDispatcher = CommandDispatcher.DEFAULT,
)

/**
 * A command result and, when the command is undoable, the invocation that undoes it.
 * The inverse is built at execution time so it can capture pre-mutation state.
 */
data class CommandResult<out R>(
    val value: R,
    val inverse: CommandInvocation? = null,
)

data class CommandInvocation(
    val commandId: CommandId,
    val params: JsonElement = JsonNull,
)

val CommandJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
}

/**
 * A typed command. [execute] returns a typed error as a value; the host catches
 * unexpected exceptions at the plugin boundary.
 */
abstract class Command<P : Any, R : Any>(
    val id: CommandId,
    val paramsSerializer: KSerializer<P>,
    val resultSerializer: KSerializer<R>,
    val doc: String,
    val policy: CommandPolicy = CommandPolicy(),
) {
    abstract suspend fun execute(params: P): Outcome<CommandResult<R>>

    fun invocation(params: P): CommandInvocation = CommandInvocation(
        commandId = id,
        params = CommandJson.encodeToJsonElement(paramsSerializer, params),
    )

    /**
     * Decodes raw parameters at the command boundary, runs the command and encodes
     * the result. Invalid parameters are [AppError.InvalidInput] values.
     */
    suspend fun invokeRaw(params: JsonElement): Outcome<CommandResult<JsonElement>> {
        val decoded = try {
            CommandJson.decodeFromJsonElement(paramsSerializer, params)
        } catch (exception: IllegalArgumentException) {
            return Outcome.Failure(AppError.InvalidInput)
        }

        return when (val outcome = execute(decoded)) {
            is Outcome.Success -> Outcome.Success(
                CommandResult(
                    value = CommandJson.encodeToJsonElement(resultSerializer, outcome.value.value),
                    inverse = outcome.value.inverse,
                ),
            )

            is Outcome.Failure -> outcome
        }
    }
}
