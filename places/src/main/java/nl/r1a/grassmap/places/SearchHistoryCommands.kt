package nl.r1a.grassmap.places

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.Outcome

/** The action that empties the search history (T-0207). */
val SEARCH_HISTORY_CLEAR_COMMAND: CommandId = CommandId("core.search.history_clear")

/** The read side the settings screen and the control channel use (T-0207). */
val SEARCH_HISTORY_LIST_COMMAND: CommandId = CommandId("core.search.history_list")

private val HISTORY_POLICY: CommandPolicy =
    CommandPolicy(
        undoable = false,
        allowlistedForControl = true,
        dispatcher = CommandDispatcher.IO,
    )

/**
 * Empties the search history completely.
 *
 * The search screen's own `clear` only clears the field; this action is the
 * history's own erase, and it works while history is off. The result names the
 * remaining entry count, which is always zero on success, so a control-channel
 * run can tell an empty store from a refused one.
 */
class SearchHistoryClearCommand(
    private val store: SearchHistoryStore,
) : Command<JsonElement, String>(
        id = SEARCH_HISTORY_CLEAR_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Empties the search history and returns the remaining entry count.",
        policy = HISTORY_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> =
        when (val cleared = store.clear()) {
            is Outcome.Failure -> {
                cleared
            }

            is Outcome.Success -> {
                when (val listed = store.list()) {
                    is Outcome.Failure -> listed
                    is Outcome.Success -> Outcome.Success(CommandResult("cleared=true entries=${listed.value.size}"))
                }
            }
        }
}

/**
 * Reports how many searches are stored. It deliberately returns no query text:
 * the control channel's output is logged, and the privacy rule keeps queries out
 * of logs (cross-cutting.md section 3). The list screen reads
 * [SearchHistoryStore.list] directly.
 */
class SearchHistoryListCommand(
    private val store: SearchHistoryStore,
) : Command<JsonElement, String>(
        id = SEARCH_HISTORY_LIST_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Returns how many searches are stored, without their text.",
        policy = HISTORY_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> =
        when (val listed = store.list()) {
            is Outcome.Failure -> listed
            is Outcome.Success -> Outcome.Success(CommandResult("entries=${listed.value.size}"))
        }
}

/** The commands the app registers for the search history (T-0207). */
fun searchHistoryCommands(store: SearchHistoryStore): List<Command<*, *>> =
    listOf(SearchHistoryClearCommand(store), SearchHistoryListCommand(store))
