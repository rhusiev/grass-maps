package nl.r1a.grassmaps.plugins.profiles.ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nl.r1a.grassmaps.plugin.api.command.Command
import nl.r1a.grassmaps.plugin.api.command.CommandDispatcher
import nl.r1a.grassmaps.plugin.api.command.CommandPolicy
import nl.r1a.grassmaps.plugin.api.command.CommandResult
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.ProfilesState

/**
 * The profile commands (T-0602). Each returns the published screen state, so
 * the control channel sees what the screen draws. They read and write the
 * profile files, so they run on the IO dispatcher. Undo is the host's history,
 * which is why no command is `undoable` in the command bus sense.
 */
private val PROFILE_POLICY: CommandPolicy =
    CommandPolicy(
        undoable = false,
        allowlistedForControl = true,
        dispatcher = CommandDispatcher.IO,
    )

/** Adds a profile that starts as a copy of [from]. Parameters: name, from. */
class CreateProfileCommand(
    private val session: ProfilesUiSession,
) : Command<JsonElement, ProfilesState>(
        id = ProfilesUiContract.create,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = ProfilesState.serializer(),
        doc = "Adds a profile that starts as a copy of another one. Parameters: name, from.",
        policy = PROFILE_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<ProfilesState>> {
        val name = params.text(PARAM_NAME) ?: return Outcome.Failure(AppError.InvalidInput)
        val from = params.text(PARAM_FROM) ?: return Outcome.Failure(AppError.InvalidInput)
        return session.create(name, from).asResult()
    }
}

/** Copies the settings of [PARAM_FROM] into [PARAM_TO]. */
class CopySettingsCommand(
    private val session: ProfilesUiSession,
) : Command<JsonElement, ProfilesState>(
        id = ProfilesUiContract.copySettings,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = ProfilesState.serializer(),
        doc = "Copies the settings of one profile into another. Parameters: from, to, and optionally groups.",
        policy = PROFILE_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<ProfilesState>> {
        val from = params.text(PARAM_FROM) ?: return Outcome.Failure(AppError.InvalidInput)
        val to = params.text(PARAM_TO) ?: return Outcome.Failure(AppError.InvalidInput)
        val groups = params.groups() ?: return Outcome.Failure(AppError.InvalidInput)
        return session.copySettings(from, to, groups).asResult()
    }
}

/** Puts the settings of [PARAM_PROFILE] back to its baseline. */
class ResetSettingsCommand(
    private val session: ProfilesUiSession,
) : Command<JsonElement, ProfilesState>(
        id = ProfilesUiContract.reset,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = ProfilesState.serializer(),
        doc = "Puts the settings of a profile back to its baseline. Parameters: profile, and optionally groups.",
        policy = PROFILE_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<ProfilesState>> {
        val profile = params.text(PARAM_PROFILE) ?: return Outcome.Failure(AppError.InvalidInput)
        val groups = params.groups() ?: return Outcome.Failure(AppError.InvalidInput)
        return session.reset(profile, groups).asResult()
    }
}

/** Reverts the last profile action. */
class UndoProfileActionCommand(
    private val session: ProfilesUiSession,
) : Command<JsonElement, ProfilesState>(
        id = ProfilesUiContract.undo,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = ProfilesState.serializer(),
        doc = "Reverts the last profile action.",
        policy = PROFILE_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<ProfilesState>> = session.undo().asResult()
}

private fun Outcome<ProfilesState>.asResult(): Outcome<CommandResult<ProfilesState>> =
    when (this) {
        is Outcome.Success -> Outcome.Success(CommandResult(value))
        is Outcome.Failure -> this
    }

private fun JsonElement.text(key: String): String? {
    val json = this as? JsonObject ?: return null
    return (json[key] as? JsonPrimitive)
        ?.takeIf { primitive -> primitive.isString }
        ?.contentOrNull
        ?.trim()
        ?.takeIf { value -> value.isNotEmpty() }
}

/** The optional `groups` array. It is empty when absent and null when malformed. */
private fun JsonElement.groups(): Set<String>? {
    val json = this as? JsonObject ?: return emptySet()
    val element = json[PARAM_GROUPS] ?: return emptySet()
    val array = element as? JsonArray ?: return null
    val names = array.map { item -> (item as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull }
    return if (names.any { it.isNullOrBlank() }) null else names.filterNotNull().toSet()
}

private const val PARAM_NAME = "name"
private const val PARAM_FROM = "from"
private const val PARAM_TO = "to"
private const val PARAM_PROFILE = "profile"
private const val PARAM_GROUPS = "groups"
