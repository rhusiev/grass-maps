package nl.r1a.grassmap.plugins.track.library

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nl.r1a.grassmap.plugin.api.command.CancellationKey
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.TrackLibraryState
import nl.r1a.grassmap.plugin.api.model.TrackSort

/**
 * The track library commands (T-0414). None is registered as undoable on the
 * command bus: each action returns a scoped receipt and [UndoTrackLibraryCommand]
 * consumes the latest one. That is deliberately not the global T-0304 stack.
 *
 * Every command returns the new library state, so the control channel and the
 * screen read exactly what was persisted.
 */
private val LIBRARY_POLICY: CommandPolicy =
    CommandPolicy(
        undoable = false,
        allowlistedForControl = true,
        dispatcher = CommandDispatcher.IO,
    )

/**
 * The three browsing controls change only the derived screen view, so they are
 * not exposed on the control channel.
 *
 * A search keystroke is cancellable: a newer keystroke stops the one still
 * running. The session also drops a result whose sequence is stale, which
 * covers the race where both reach the mutex in the wrong order.
 */
private val QUERY_POLICY: CommandPolicy =
    CommandPolicy(
        cancellableBy = CancellationKey(QUERY_CANCELLATION_KEY),
        undoable = false,
        allowlistedForControl = false,
        dispatcher = CommandDispatcher.IO,
    )

private val VIEW_POLICY: CommandPolicy =
    CommandPolicy(
        undoable = false,
        allowlistedForControl = false,
        dispatcher = CommandDispatcher.IO,
    )

/** Creates a folder and puts it at the end of the folder order. */
class CreateTrackFolderCommand(
    private val session: TrackLibrarySession,
) : Command<JsonElement, TrackLibraryState>(
        id = TrackLibraryContract.createFolder,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = TrackLibraryState.serializer(),
        doc = "Creates a track folder. Parameter: name.",
        policy = LIBRARY_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<TrackLibraryState>> {
        val name = params.text(PARAM_NAME) ?: return Outcome.Failure(AppError.InvalidInput)
        return session.result(session.createFolder(name))
    }

    private companion object {
        const val PARAM_NAME = "name"
    }
}

/** Renames a folder; the tracks that name it stay in it. */
class RenameTrackFolderCommand(
    private val session: TrackLibrarySession,
) : Command<JsonElement, TrackLibraryState>(
        id = TrackLibraryContract.renameFolder,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = TrackLibraryState.serializer(),
        doc = "Renames a track folder. Parameters: folderId, name.",
        policy = LIBRARY_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<TrackLibraryState>> {
        val folderId = params.text(PARAM_FOLDER_ID) ?: return Outcome.Failure(AppError.InvalidInput)
        val name = params.text(PARAM_NAME) ?: return Outcome.Failure(AppError.InvalidInput)
        return session.result(session.renameFolder(folderId, name))
    }

    private companion object {
        const val PARAM_FOLDER_ID = "folderId"
        const val PARAM_NAME = "name"
    }
}

/**
 * Renames, describes and files one track. An empty name or description clears
 * it and an empty folderId unfiles it. Parameters: trackId, name, description,
 * and optionally folderId.
 */
class EditTrackCommand(
    private val session: TrackLibrarySession,
) : Command<JsonElement, TrackLibraryState>(
        id = TrackLibraryContract.editTrack,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = TrackLibraryState.serializer(),
        doc = "Renames, describes and files a track. Parameters: trackId, name, description, and optionally folderId.",
        policy = LIBRARY_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<TrackLibraryState>> {
        val trackId = params.text(PARAM_TRACK_ID) ?: return Outcome.Failure(AppError.InvalidInput)
        val name = params.rawText(PARAM_NAME) ?: return Outcome.Failure(AppError.InvalidInput)
        val description = params.rawText(PARAM_DESCRIPTION) ?: return Outcome.Failure(AppError.InvalidInput)
        val folderId = params.text(PARAM_FOLDER_ID)
        return session.result(session.editTrack(trackId, name.trim().ifEmpty { null }, description.ifEmpty { null }, folderId))
    }

    private companion object {
        const val PARAM_TRACK_ID = "trackId"
        const val PARAM_NAME = "name"
        const val PARAM_DESCRIPTION = "description"
        const val PARAM_FOLDER_ID = "folderId"
    }
}

/** Moves a track to a folder, or out of every folder when [PARAM_FOLDER_ID] is empty. */
class MoveTrackCommand(
    private val session: TrackLibrarySession,
) : Command<JsonElement, TrackLibraryState>(
        id = TrackLibraryContract.moveTrack,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = TrackLibraryState.serializer(),
        doc = "Moves a track to a folder. Parameters: trackId, and optionally folderId; an empty folderId unfiles it.",
        policy = LIBRARY_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<TrackLibraryState>> {
        val trackId = params.text(PARAM_TRACK_ID) ?: return Outcome.Failure(AppError.InvalidInput)
        return session.result(session.moveTrack(trackId, params.text(PARAM_FOLDER_ID)))
    }

    private companion object {
        const val PARAM_TRACK_ID = "trackId"
        const val PARAM_FOLDER_ID = "folderId"
    }
}

/** Deletes one imported track; the receipt restores it to its folder. */
class DeleteTrackCommand(
    private val session: TrackLibrarySession,
) : Command<JsonElement, TrackLibraryState>(
        id = TrackLibraryContract.deleteTrack,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = TrackLibraryState.serializer(),
        doc = "Deletes an imported track. Parameter: trackId.",
        policy = LIBRARY_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<TrackLibraryState>> {
        val trackId = params.text(PARAM_TRACK_ID) ?: return Outcome.Failure(AppError.InvalidInput)
        return session.result(session.deleteTrack(trackId))
    }

    private companion object {
        const val PARAM_TRACK_ID = "trackId"
    }
}

/** Consumes the latest scoped receipt; with none it is a typed no-op. */
class UndoTrackLibraryCommand(
    private val session: TrackLibrarySession,
) : Command<JsonElement, TrackLibraryState>(
        id = TrackLibraryContract.undo,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = TrackLibraryState.serializer(),
        doc = "Undoes the latest track library action and returns the state.",
        policy = LIBRARY_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<TrackLibraryState>> = session.result(session.undo())
}

/** Applies a search query to the track names. Parameter: query, and optionally sequence. */
class SearchTracksCommand(
    private val session: TrackLibrarySession,
) : Command<JsonElement, TrackLibraryState>(
        id = TrackLibraryContract.setQuery,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = TrackLibraryState.serializer(),
        doc = "Filters the track library by name. Parameters: query, and optionally sequence.",
        policy = QUERY_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<TrackLibraryState>> {
        val query = params.rawText(PARAM_QUERY) ?: return Outcome.Failure(AppError.InvalidInput)
        val sequence = params.long(PARAM_SEQUENCE) ?: 0L
        return session.result(session.setQuery(query, sequence))
    }

    private companion object {
        const val PARAM_QUERY = "query"
        const val PARAM_SEQUENCE = "sequence"
    }
}

/** Selects one of the four fixed track orders. */
class SortTracksCommand(
    private val session: TrackLibrarySession,
) : Command<JsonElement, TrackLibraryState>(
        id = TrackLibraryContract.setSort,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = TrackLibraryState.serializer(),
        doc = "Sorts the track library. Parameter: sort, one of newest, name, distance or duration.",
        policy = VIEW_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<TrackLibraryState>> {
        val sort = params.text(PARAM_SORT)?.let(TrackSort::fromWire) ?: return Outcome.Failure(AppError.InvalidInput)
        return session.result(session.setSort(sort))
    }

    private companion object {
        const val PARAM_SORT = "sort"
    }
}

/** Filters the rows to one folder, or to every folder with an empty folderId. */
class SelectTrackFolderCommand(
    private val session: TrackLibrarySession,
) : Command<JsonElement, TrackLibraryState>(
        id = TrackLibraryContract.selectFolder,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = TrackLibraryState.serializer(),
        doc = "Filters the track library to one folder. Parameter: folderId, empty for every folder.",
        policy = VIEW_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<TrackLibraryState>> =
        session.result(session.selectFolder(params.text(PARAM_FOLDER_ID)))

    private companion object {
        const val PARAM_FOLDER_ID = "folderId"
    }
}

private fun TrackLibrarySession.result(outcome: Outcome<TrackLibraryState>): Outcome<CommandResult<TrackLibraryState>> =
    when (outcome) {
        is Outcome.Success -> Outcome.Success(CommandResult(outcome.value))
        is Outcome.Failure -> outcome
    }

private fun JsonElement.text(key: String): String? {
    val json = this as? JsonObject ?: return null
    return (json[key] as? JsonPrimitive)
        ?.takeIf { primitive -> primitive.isString }
        ?.contentOrNull
        ?.trim()
        ?.takeIf { value -> value.isNotEmpty() }
}

/** A string parameter that may be empty, such as a cleared name or search query. */
private fun JsonElement.rawText(key: String): String? {
    val json = this as? JsonObject ?: return null
    return (json[key] as? JsonPrimitive)
        ?.takeIf { primitive -> primitive.isString }
        ?.contentOrNull
}

/** A numeric parameter; the JSON may carry it as a string or a number. */
private fun JsonElement.long(key: String): Long? {
    val json = this as? JsonObject ?: return null
    return (json[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
}

private const val QUERY_CANCELLATION_KEY = "track.library.query"
