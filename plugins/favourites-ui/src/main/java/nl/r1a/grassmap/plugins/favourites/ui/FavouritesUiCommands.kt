package nl.r1a.grassmap.plugins.favourites.ui

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.FavouriteListsState
import nl.r1a.grassmap.plugin.api.model.FavouriteSort
import nl.r1a.grassmap.plugin.api.model.FavouriteSpecial

/**
 * The lists commands (T-0213). None is registered as undoable on the command
 * bus: each action returns a scoped receipt and [UndoFavouriteListCommand]
 * consumes the latest one. That is deliberately not the global T-0304 stack.
 *
 * Every command returns the new lists state, so the control channel and the
 * sheet read exactly what was persisted.
 */
private val LISTS_POLICY: CommandPolicy =
    CommandPolicy(
        undoable = false,
        allowlistedForControl = true,
        dispatcher = CommandDispatcher.IO,
    )

/**
 * The two browsing controls of T-0214 change only the derived screen view, so
 * they are not exposed on the control channel.
 */
private val BROWSE_POLICY: CommandPolicy =
    CommandPolicy(
        undoable = false,
        allowlistedForControl = false,
        dispatcher = CommandDispatcher.IO,
    )

/** Creates a list with a name, a colour and an optional icon. */
class CreateFavouriteListCommand(
    private val session: FavouritesUiSession,
) : Command<JsonElement, FavouriteListsState>(
        id = FavouritesUiContract.createList,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = FavouriteListsState.serializer(),
        doc = "Creates a favourites list. Parameters: name, color, and optionally icon.",
        policy = LISTS_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<FavouriteListsState>> {
        val name = params.text(PARAM_NAME) ?: return Outcome.Failure(AppError.InvalidInput)
        val color = params.text(PARAM_COLOR) ?: return Outcome.Failure(AppError.InvalidInput)
        return session.result(session.createList(name, color, params.text(PARAM_ICON)))
    }

    private companion object {
        const val PARAM_NAME = "name"
        const val PARAM_COLOR = "color"
        const val PARAM_ICON = "icon"
    }
}

/** Moves one favourite into another list. */
class MoveFavouritePointCommand(
    private val session: FavouritesUiSession,
) : Command<JsonElement, FavouriteListsState>(
        id = FavouritesUiContract.movePoint,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = FavouriteListsState.serializer(),
        doc = "Moves a favourite to another list. Parameters: pointId, list.",
        policy = LISTS_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<FavouriteListsState>> {
        val pointId = params.text(PARAM_POINT_ID) ?: return Outcome.Failure(AppError.InvalidInput)
        val list = params.text(PARAM_LIST) ?: return Outcome.Failure(AppError.InvalidInput)
        return session.result(session.movePoint(pointId, list))
    }

    private companion object {
        const val PARAM_POINT_ID = "pointId"
        const val PARAM_LIST = "list"
    }
}

/** Recolours one whole list and every point in it. */
class RecolourFavouriteListCommand(
    private val session: FavouritesUiSession,
) : Command<JsonElement, FavouriteListsState>(
        id = FavouritesUiContract.recolourList,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = FavouriteListsState.serializer(),
        doc = "Recolours a list and all its points. Parameters: list, color.",
        policy = LISTS_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<FavouriteListsState>> {
        val list = params.text(PARAM_LIST) ?: return Outcome.Failure(AppError.InvalidInput)
        val color = params.text(PARAM_COLOR) ?: return Outcome.Failure(AppError.InvalidInput)
        return session.result(session.recolourList(list, color))
    }

    private companion object {
        const val PARAM_LIST = "list"
        const val PARAM_COLOR = "color"
    }
}

/** Sets one favourite as Home. */
class SetHomeFavouriteCommand(
    session: FavouritesUiSession,
) : SetSpecialFavouriteCommand(
        id = FavouritesUiContract.setHome,
        doc = "Sets a favourite as Home. Parameter: pointId.",
        special = FavouriteSpecial.HOME,
        session = session,
    )

/** Sets one favourite as Work. */
class SetWorkFavouriteCommand(
    session: FavouritesUiSession,
) : SetSpecialFavouriteCommand(
        id = FavouritesUiContract.setWork,
        doc = "Sets a favourite as Work. Parameter: pointId.",
        special = FavouriteSpecial.WORK,
        session = session,
    )

/** The Home and Work commands differ only in the special they set. */
abstract class SetSpecialFavouriteCommand(
    id: CommandId,
    doc: String,
    private val special: FavouriteSpecial,
    private val session: FavouritesUiSession,
) : Command<JsonElement, FavouriteListsState>(
        id = id,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = FavouriteListsState.serializer(),
        doc = doc,
        policy = LISTS_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<FavouriteListsState>> {
        val pointId = params.text(PARAM_POINT_ID) ?: return Outcome.Failure(AppError.InvalidInput)
        return session.result(session.setSpecial(pointId, special))
    }

    private companion object {
        const val PARAM_POINT_ID = "pointId"
    }
}

/** Consumes the latest scoped receipt; with none it is a typed no-op. */
class UndoFavouriteListCommand(
    private val session: FavouritesUiSession,
) : Command<JsonElement, FavouriteListsState>(
        id = FavouritesUiContract.undo,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = FavouriteListsState.serializer(),
        doc = "Undoes the latest lists action and returns the state.",
        policy = LISTS_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<FavouriteListsState>> = session.result(session.undo())
}

/**
 * Applies a search query to the favourites screen (T-0214). The query is
 * trimmed; a blank query restores every point.
 */
class SearchFavouritesCommand(
    private val session: FavouritesUiSession,
) : Command<JsonElement, FavouriteListsState>(
        id = FavouritesUiContract.setQuery,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = FavouriteListsState.serializer(),
        doc = "Filters the favourites screen by name and description. Parameter: query.",
        policy = BROWSE_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<FavouriteListsState>> {
        val query = params.rawText(PARAM_QUERY) ?: return Outcome.Failure(AppError.InvalidInput)
        return session.result(session.setQuery(query))
    }

    private companion object {
        const val PARAM_QUERY = "query"
    }
}

/** Selects one of the four fixed favourites orders (T-0214). */
class SortFavouritesCommand(
    private val session: FavouritesUiSession,
) : Command<JsonElement, FavouriteListsState>(
        id = FavouritesUiContract.setSort,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = FavouriteListsState.serializer(),
        doc = "Sorts the favourites screen. Parameter: sort, one of insertion, name_ascending, name_descending or list_then_name.",
        policy = BROWSE_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<FavouriteListsState>> {
        val sort = params.text(PARAM_SORT)?.let(FavouriteSort::fromWire) ?: return Outcome.Failure(AppError.InvalidInput)
        return session.result(session.setSort(sort))
    }

    private companion object {
        const val PARAM_SORT = "sort"
    }
}

private fun FavouritesUiSession.result(outcome: Outcome<FavouriteListsState>): Outcome<CommandResult<FavouriteListsState>> =
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

/** A string parameter that may be empty, such as a cleared search query. */
private fun JsonElement.rawText(key: String): String? {
    val json = this as? JsonObject ?: return null
    return (json[key] as? JsonPrimitive)
        ?.takeIf { primitive -> primitive.isString }
        ?.contentOrNull
}
