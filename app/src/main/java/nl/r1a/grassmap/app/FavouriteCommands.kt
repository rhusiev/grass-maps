package nl.r1a.grassmap.app

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nl.r1a.grassmap.places.Favourite
import nl.r1a.grassmap.places.FavouriteId
import nl.r1a.grassmap.places.FavouriteShape
import nl.r1a.grassmap.places.favourite
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.util.Locale

/** The command ids of the favourites seam. */
internal object FavouriteCommandIds {
    val ADD: CommandId = CommandId("core.favourite.add")
    val DELETE: CommandId = CommandId("core.favourite.delete")
    val UNDO_DELETE: CommandId = CommandId("core.favourite.undo_delete")
    val IMPORT: CommandId = CommandId("core.favourite.import")
    val EXPORT: CommandId = CommandId("core.favourite.export")

    val ALL: List<CommandId> = listOf(ADD, DELETE, UNDO_DELETE, IMPORT, EXPORT)
}

/**
 * Every favourites command runs on IO and none is undoable on the command bus:
 * deletion has its own scoped receipt, and the general undo stack is T-0304's.
 * They are allowlisted so the control channel can exercise the seam on a
 * device, where the AI-control switch still gates them.
 */
private val FAVOURITE_POLICY: CommandPolicy =
    CommandPolicy(
        undoable = false,
        allowlistedForControl = true,
        dispatcher = CommandDispatcher.IO,
    )

internal fun favouriteCommands(
    favourites: () -> FavouritesAccess,
    assetReader: (String) -> ByteArray?,
): List<Command<*, *>> =
    listOf(
        AddFavouriteCommand(favourites),
        DeleteFavouriteCommand(favourites),
        UndoDeleteFavouriteCommand(favourites),
        ImportFavouritesCommand(favourites, assetReader),
        ExportFavouritesCommand(favourites),
    )

/**
 * Adds one favourite and returns its id. Parameters:
 * `lat`, `lon`, `name`, and optionally `description`, `group`, `color`, `icon`,
 * `shape` (`circle`, `square` or `octagon`).
 */
internal class AddFavouriteCommand(
    private val favourites: () -> FavouritesAccess,
) : Command<JsonElement, String>(
        id = FavouriteCommandIds.ADD,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Adds a favourite and returns its id.",
        policy = FAVOURITE_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val request = request(params) ?: return Outcome.Failure(AppError.InvalidInput)
        val record =
            favourite(
                latitude = request.latitude,
                longitude = request.longitude,
                name = request.name,
                description = request.description,
                group = request.group,
                color = request.color ?: Favourite.DEFAULT_COLOR,
                icon = request.icon,
                shape = request.shape,
            )
        return when (val outcome = favourites().add(record)) {
            is Outcome.Success -> Outcome.Success(CommandResult(outcome.value.id.value))
            is Outcome.Failure -> outcome
        }
    }

    private fun request(params: JsonElement): Request? {
        val json = params as? JsonObject ?: return null
        val latitude = json.number(PARAM_LATITUDE) ?: return null
        val longitude = json.number(PARAM_LONGITUDE) ?: return null
        val name = json.text(PARAM_NAME)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Request(
            latitude = latitude,
            longitude = longitude,
            name = name,
            description = json.optional(PARAM_DESCRIPTION),
            group = json.optional(PARAM_GROUP),
            color = json.optional(PARAM_COLOR),
            icon = json.optional(PARAM_ICON),
            shape = shape(json.text(PARAM_SHAPE)) ?: return null,
        )
    }

    private fun shape(text: String?): FavouriteShape? {
        val name = text?.trim()?.lowercase(Locale.ROOT) ?: return FavouriteShape.Circle
        if (name.isEmpty()) {
            return FavouriteShape.Circle
        }
        return FavouriteShape.entries.firstOrNull { it.wireName == name }
    }

    private data class Request(
        val latitude: Double,
        val longitude: Double,
        val name: String,
        val description: String?,
        val group: String?,
        val color: String?,
        val icon: String?,
        val shape: FavouriteShape,
    )

    private companion object {
        const val PARAM_LATITUDE = "lat"
        const val PARAM_LONGITUDE = "lon"
        const val PARAM_NAME = "name"
        const val PARAM_DESCRIPTION = "description"
        const val PARAM_GROUP = "group"
        const val PARAM_COLOR = "color"
        const val PARAM_ICON = "icon"
        const val PARAM_SHAPE = "shape"
    }
}

/**
 * Deletes one favourite and returns the undo token. The command is not
 * registered as undoable; undo is [UndoDeleteFavouriteCommand].
 */
internal class DeleteFavouriteCommand(
    private val favourites: () -> FavouritesAccess,
) : Command<JsonElement, String>(
        id = FavouriteCommandIds.DELETE,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Deletes a favourite and returns the token that restores it.",
        policy = FAVOURITE_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val id = id(params) ?: return Outcome.Failure(AppError.InvalidInput)
        return when (val outcome = favourites().delete(id)) {
            is Outcome.Success -> Outcome.Success(CommandResult(outcome.value.favourite.id.value))
            is Outcome.Failure -> outcome
        }
    }
}

/** Restores one deleted favourite from its token. */
internal class UndoDeleteFavouriteCommand(
    private val favourites: () -> FavouritesAccess,
) : Command<JsonElement, String>(
        id = FavouriteCommandIds.UNDO_DELETE,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Restores a deleted favourite and returns its id.",
        policy = FAVOURITE_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val id = id(params) ?: return Outcome.Failure(AppError.InvalidInput)
        return when (val outcome = favourites().undoDelete(id)) {
            is Outcome.Success -> Outcome.Success(CommandResult(outcome.value.id.value))
            is Outcome.Failure -> outcome
        }
    }
}

/** Imports an OsmAnd favourites GPX from an asset name or an absolute path. */
internal class ImportFavouritesCommand(
    private val favourites: () -> FavouritesAccess,
    private val assetReader: (String) -> ByteArray?,
) : Command<JsonElement, String>(
        id = FavouriteCommandIds.IMPORT,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Imports a favourites GPX file and reports how many points it added.",
        policy = FAVOURITE_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val path = path(params) ?: return Outcome.Failure(AppError.InvalidInput)
        val bytes =
            when (val read = readCommandFile(path, assetReader)) {
                is Outcome.Success -> read.value
                is Outcome.Failure -> return read
            }
        return when (val outcome = favourites().import(bytes)) {
            is Outcome.Success -> {
                Outcome.Success(
                    CommandResult("imported=${outcome.value.imported} skipped=${outcome.value.skipped.size}"),
                )
            }

            is Outcome.Failure -> {
                outcome
            }
        }
    }
}

/** Exports the library as an OsmAnd favourites GPX at an absolute path. */
internal class ExportFavouritesCommand(
    private val favourites: () -> FavouritesAccess,
) : Command<JsonElement, String>(
        id = FavouriteCommandIds.EXPORT,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Writes the favourites library as a GPX file and returns its byte count.",
        policy = FAVOURITE_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val path = path(params) ?: return Outcome.Failure(AppError.InvalidInput)
        val bytes =
            when (val outcome = favourites().export()) {
                is Outcome.Success -> outcome.value
                is Outcome.Failure -> return outcome
            }
        return when (val written = writeCommandFile(path, bytes)) {
            is Outcome.Success -> Outcome.Success(CommandResult("exported=${bytes.size}"))
            is Outcome.Failure -> written
        }
    }
}

/** The `id` parameter shared by delete and undo-delete. */
private fun id(params: JsonElement): FavouriteId? {
    val json = params as? JsonObject ?: return null
    val value = json.text(PARAM_ID)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return FavouriteId(value)
}

/** The `path` parameter shared by import and export. */
private fun path(params: JsonElement): String? {
    val json = params as? JsonObject ?: return null
    return json.text(PARAM_PATH)?.trim()?.takeIf { it.isNotEmpty() }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content

private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

private fun JsonObject.optional(key: String): String? = text(key)?.trim()?.takeIf { it.isNotEmpty() }

private const val PARAM_ID = "id"
private const val PARAM_PATH = "path"
