package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * Runs [onSuccess] after a successful execution of the wrapped command.
 *
 * The favourites backup hangs off the command bus this way: the favourites
 * commands stay untouched, and only a mutation that succeeded schedules a copy.
 */
internal class BackupObservedCommand<P : Any, R : Any>(
    private val delegate: Command<P, R>,
    private val onSuccess: () -> Unit,
) : Command<P, R>(
        id = delegate.id,
        paramsSerializer = delegate.paramsSerializer,
        resultSerializer = delegate.resultSerializer,
        doc = delegate.doc,
        policy = delegate.policy,
    ) {
    override suspend fun execute(params: P): Outcome<CommandResult<R>> =
        when (val outcome = delegate.execute(params)) {
            is Outcome.Success -> {
                onSuccess()
                outcome
            }

            is Outcome.Failure -> {
                outcome
            }
        }
}

/** The favourites commands that change the library; export does not. */
internal val FAVOURITE_MUTATION_IDS: Set<CommandId> =
    setOf(
        FavouriteCommandIds.ADD,
        FavouriteCommandIds.DELETE,
        FavouriteCommandIds.UNDO_DELETE,
        FavouriteCommandIds.IMPORT,
    )

/**
 * Wraps one favourites command in [BackupObservedCommand] when it mutates the
 * library, and returns it unchanged otherwise.
 */
@Suppress("UNCHECKED_CAST")
internal fun observedFavouriteCommand(
    command: Command<*, *>,
    onSuccess: () -> Unit,
): Command<*, *> =
    if (command.id in FAVOURITE_MUTATION_IDS) {
        // invokeRaw decodes and encodes with the delegate's own serializers, so
        // the star-projected command is the same command with its types erased.
        BackupObservedCommand(command as Command<Any, Any>, onSuccess)
    } else {
        command
    }
