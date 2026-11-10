package nl.r1a.grassmap.places

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import nl.r1a.grassmap.plugin.api.command.CancellationKey
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.SearchContext
import java.util.concurrent.TimeUnit

/** The screen keystroke command the shell sends through the command bus. */
val SEARCH_SCREEN_COMMAND: CommandId = CommandId("core.search.screen")

/** The replay harness command; the phone script types the committed probe set. */
val SEARCH_REPLAY_COMMAND: CommandId = CommandId("core.search.replay")

/** The cancellation key every screen keystroke shares, so a newer one wins. */
const val SEARCH_SCREEN_CANCELLATION_KEY: String = "core.search.screen.query"

private const val DEFAULT_REPLAY_RUNS = 1
private const val MAX_REPLAY_QUERIES = 512

/**
 * The keystroke policy. A newer keystroke cancels the running one through the
 * command bus; the session drops a stale sequence on top of that.
 */
private val SCREEN_POLICY: CommandPolicy =
    CommandPolicy(
        cancellableBy = CancellationKey(SEARCH_SCREEN_CANCELLATION_KEY),
        undoable = false,
        allowlistedForControl = true,
        dispatcher = CommandDispatcher.IO,
    )

private val REPLAY_POLICY: CommandPolicy =
    CommandPolicy(
        undoable = false,
        allowlistedForControl = true,
        dispatcher = CommandDispatcher.IO,
    )

/**
 * One search keystroke from the screen (T-0203).
 *
 * Parameters: `query`, `sequence`, and optionally `index`, `limit`, `position`,
 * `viewport` and `clear`. `viewport` is `{"south": .., "west": .., "north": ..,
 * "east": ..}` and restricts every result to the visible map area, which is
 * "search this area" (T-0206). A blank query or `clear: true` empties the
 * screen without reaching the provider. The result is a one-line summary; the
 * screen itself reads [SearchScreenSession.state].
 */
class SearchScreenCommand(
    private val session: SearchScreenSession,
) : Command<JsonElement, String>(
        id = SEARCH_SCREEN_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Sends one search screen keystroke. Parameters: query, sequence, and optionally index, limit, position, viewport or clear.",
        policy = SCREEN_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val request = parse(params) ?: return Outcome.Failure(AppError.InvalidInput)
        if (request.clear) {
            val cleared = session.clear()
            return Outcome.Success(CommandResult("cleared=true query=\"${cleared.query}\""))
        }
        return when (val outcome = session.submit(request.query, request.sequence, request.index, request.limit, request.context)) {
            is Outcome.Failure -> {
                outcome
            }

            is Outcome.Success -> {
                val state = outcome.value
                Outcome.Success(
                    CommandResult(
                        "sequence=${state.sequence} status=${state.status} hits=${state.hits.size} " +
                            "top=\"${state.hits.firstOrNull()?.name ?: ""}\"",
                    ),
                )
            }
        }
    }

    private fun parse(params: JsonElement): Request? {
        val json = params as? JsonObject ?: if (params is JsonNull) JsonObject(emptyMap()) else return null
        val clear = (json[PARAM_CLEAR] as? JsonPrimitive)?.booleanOrNull ?: false
        val query = (json[PARAM_QUERY] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        if (!clear && query == null) {
            return null
        }
        val sequence = (json[PARAM_SEQUENCE] as? JsonPrimitive)?.longOrNull ?: 0L
        val index = (json[PARAM_INDEX] as? JsonPrimitive)?.contentOrNull ?: SearchScreenSession.DEFAULT_INDEX
        val limit = (json[PARAM_LIMIT] as? JsonPrimitive)?.intOrNull ?: SearchScreenSession.DEFAULT_LIMIT
        if (limit !in 1..MAX_SEARCH_RESULTS) {
            return null
        }
        val position =
            (json[PARAM_POSITION] as? JsonObject)?.let { point ->
                val latitude = (point["lat"] as? JsonPrimitive)?.doubleOrNull ?: return null
                val longitude = (point["lon"] as? JsonPrimitive)?.doubleOrNull ?: return null
                GeoPoint(latitude, longitude)
            }
        val viewport =
            (json[PARAM_VIEWPORT] as? JsonObject)?.let { box ->
                val south = (box["south"] as? JsonPrimitive)?.doubleOrNull ?: return null
                val west = (box["west"] as? JsonPrimitive)?.doubleOrNull ?: return null
                val north = (box["north"] as? JsonPrimitive)?.doubleOrNull ?: return null
                val east = (box["east"] as? JsonPrimitive)?.doubleOrNull ?: return null
                BoundingBox(
                    southWest = GeoPoint(south, west),
                    northEast = GeoPoint(north, east),
                ).takeIf { it.isValid() } ?: return null
            }
        return Request(query ?: "", sequence, index, limit, SearchContext(viewport = viewport, position = position), clear)
    }

    private data class Request(
        val query: String,
        val sequence: Long,
        val index: String,
        val limit: Int,
        val context: SearchContext,
        val clear: Boolean,
    )

    private companion object {
        const val PARAM_QUERY = "query"
        const val PARAM_SEQUENCE = "sequence"
        const val PARAM_INDEX = "index"
        const val PARAM_LIMIT = "limit"
        const val PARAM_POSITION = "position"
        const val PARAM_VIEWPORT = "viewport"
        const val PARAM_CLEAR = "clear"
    }
}

/**
 * The keystroke budget harness (T-0203).
 *
 * It types every committed probe query letter by letter through the same
 * session the screen uses, and times each keystroke from the call into the
 * session to the published state. That span includes the publish, the command
 * dispatcher and the uniffi bridge to the Rust reader, so the number is the
 * keystroke budget rather than a raw index timing.
 *
 * Parameters: `queries` (array of strings, required), and optionally `index`,
 * `limit`, `position`, `viewport`, `runs`. Runs are pooled; the first keystroke
 * of the first run is reported separately because it pays the cold index open.
 */
class ReplaySearchCommand(
    private val session: SearchScreenSession,
    private val log: (String) -> Unit = {},
) : Command<JsonElement, String>(
        id = SEARCH_REPLAY_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc =
            "Types queries letter by letter and reports p50/p95/p99 per keystroke. " +
                "Parameters: queries, index, limit, position, viewport, runs.",
        policy = REPLAY_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val request = parse(params) ?: return Outcome.Failure(AppError.InvalidInput)
        session.clear()
        val timings = mutableListOf<Long>()
        var coldMicros = 0L
        var sequences = 0L
        var hits = 0
        for (run in 0 until request.runs) {
            for (query in request.queries) {
                for (length in 1..query.length) {
                    val typed = query.take(length)
                    sequences += 1
                    val started = System.nanoTime()
                    val outcome =
                        session.submit(
                            query = typed,
                            sequence = sequences,
                            index = request.index,
                            limit = request.limit,
                            context = request.context,
                        )
                    val elapsed = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started)
                    when (outcome) {
                        is Outcome.Failure -> {
                            log("replay: keystroke=\"$typed\" failed=${outcome.error}")
                            return outcome
                        }

                        is Outcome.Success -> {
                            hits = outcome.value.hits.size
                        }
                    }
                    timings += elapsed
                    if (run == 0 && sequences == 1L) {
                        coldMicros = elapsed
                    }
                }
            }
        }
        val summary =
            "index=${request.index} keystrokes=${timings.size} runs=${request.runs} " +
                "p50_ms=${millis(percentile(timings, 50))} " +
                "p95_ms=${millis(percentile(timings, 95))} " +
                "p99_ms=${millis(percentile(timings, 99))} " +
                "max_ms=${millis(timings.maxOrNull() ?: 0L)} " +
                "cold_ms=${millis(coldMicros)} hits_last=$hits"
        log("replay: $summary")
        return Outcome.Success(CommandResult(summary))
    }

    private fun parse(params: JsonElement): Request? {
        val json = params as? JsonObject ?: return null
        val queries =
            (json[PARAM_QUERIES] as? JsonArray)
                ?.mapNotNull { element -> (element as? JsonPrimitive)?.contentOrNull?.trim() }
                ?.filter { it.isNotEmpty() }
                ?: return null
        if (queries.isEmpty() || queries.size > MAX_REPLAY_QUERIES) {
            return null
        }
        val index = (json[PARAM_INDEX] as? JsonPrimitive)?.contentOrNull ?: SearchScreenSession.DEFAULT_INDEX
        val limit = (json[PARAM_LIMIT] as? JsonPrimitive)?.intOrNull ?: SearchScreenSession.DEFAULT_LIMIT
        if (limit !in 1..MAX_SEARCH_RESULTS) {
            return null
        }
        val runs = (json[PARAM_RUNS] as? JsonPrimitive)?.intOrNull ?: DEFAULT_REPLAY_RUNS
        if (runs !in 1..MAX_REPLAY_RUNS) {
            return null
        }
        val position =
            (json[PARAM_POSITION] as? JsonObject)?.let { point ->
                val latitude = (point["lat"] as? JsonPrimitive)?.doubleOrNull ?: return null
                val longitude = (point["lon"] as? JsonPrimitive)?.doubleOrNull ?: return null
                GeoPoint(latitude, longitude)
            }
        val viewport =
            (json[PARAM_VIEWPORT] as? JsonObject)?.let { box ->
                val south = (box["south"] as? JsonPrimitive)?.doubleOrNull ?: return null
                val west = (box["west"] as? JsonPrimitive)?.doubleOrNull ?: return null
                val north = (box["north"] as? JsonPrimitive)?.doubleOrNull ?: return null
                val east = (box["east"] as? JsonPrimitive)?.doubleOrNull ?: return null
                BoundingBox(
                    southWest = GeoPoint(south, west),
                    northEast = GeoPoint(north, east),
                ).takeIf { it.isValid() } ?: return null
            }
        return Request(queries, index, limit, SearchContext(viewport = viewport, position = position), runs)
    }

    private data class Request(
        val queries: List<String>,
        val index: String,
        val limit: Int,
        val context: SearchContext,
        val runs: Int,
    )

    private companion object {
        const val PARAM_QUERIES = "queries"
        const val PARAM_INDEX = "index"
        const val PARAM_LIMIT = "limit"
        const val PARAM_POSITION = "position"
        const val PARAM_VIEWPORT = "viewport"
        const val PARAM_RUNS = "runs"
        const val MAX_REPLAY_RUNS = 20
    }
}

/** The commands the app registers for the search screen and its harness. */
fun searchScreenCommands(
    session: SearchScreenSession,
    log: (String) -> Unit = {},
): List<Command<*, *>> = listOf(SearchScreenCommand(session), ReplaySearchCommand(session, log))

/**
 * The nearest-rank percentile of [timings] in microseconds. Empty input is 0,
 * so a report never crashes on a query set with no keystrokes.
 */
internal fun percentile(
    timings: List<Long>,
    percent: Int,
): Long {
    if (timings.isEmpty()) {
        return 0L
    }
    val sorted = timings.sorted()
    val rank = (percent * (sorted.size - 1)) / 100
    return sorted[rank]
}

/** Microseconds as a fixed two-decimal millisecond value for a report line. */
internal fun millis(micros: Long): String = String.format(java.util.Locale.ROOT, "%.2f", micros / 1000.0)
