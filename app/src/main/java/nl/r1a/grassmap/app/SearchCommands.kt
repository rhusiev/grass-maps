package nl.r1a.grassmap.app

import android.util.Log
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import nl.r1a.grassmap.places.MAX_SEARCH_RESULTS
import nl.r1a.grassmap.places.PlacesAccess
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.PlaceHit
import nl.r1a.grassmap.plugin.api.provider.SearchContext
import java.util.concurrent.TimeUnit

/** The debug trigger the control channel invokes to prove the search seam. */
internal val SEARCH_QUERY_COMMAND: CommandId = CommandId("core.search.query")

private const val TAG = "GrassMapsSearch"
private const val DEFAULT_INDEX = "lviv"
private const val DEFAULT_LIMIT = 5

/**
 * Runs a query keystroke by keystroke through the real offline provider and
 * logs each prefix's hits and milliseconds. With `{"cancel": true}` it runs an
 * already-superseded keystroke and reports that no result came back.
 *
 * The command is a host command with no UI binding; only the debug control
 * channel can invoke it. T-0203 owns the search screen.
 */
internal class SearchQueryCommand(
    private val places: () -> PlacesAccess,
    private val log: (String) -> Unit = { message -> Log.i(TAG, message) },
) : Command<JsonElement, String>(
        id = SEARCH_QUERY_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Runs a search query keystroke by keystroke and reports hits and latency.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val selection = parse(params) ?: return Outcome.Failure(AppError.InvalidInput)
        val access = places()
        val cancellation = if (selection.cancelled) CancellationHandle { true } else CancellationHandle.None

        if (selection.cancelled) {
            return runCancelled(access, selection, cancellation)
        }

        val timings = mutableListOf<Long>()
        var top: PlaceHit? = null
        for (length in 1..selection.query.length) {
            val typed = selection.query.take(length)
            val started = System.nanoTime()
            val outcome =
                access.search(
                    indexName = selection.index,
                    query = typed,
                    context = SearchContext(position = selection.position),
                    limit = selection.limit,
                    cancellation = cancellation,
                )
            val elapsed = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started)
            timings += elapsed
            val hits =
                when (outcome) {
                    is Outcome.Success -> {
                        outcome.value
                    }

                    is Outcome.Failure -> {
                        log("keystroke=\"$typed\" ms=${elapsed / 1000.0} failed=${outcome.error}")
                        return outcome
                    }
                }
            top = hits.firstOrNull()
            log(
                "keystroke=\"$typed\" ms=${elapsed / 1000.0} hits=${hits.size} " +
                    "top=\"${top?.name ?: ""}\"",
            )
        }
        val total = timings.sum()
        val summary =
            "index=${selection.index} query=\"${selection.query}\" " +
                "keystrokes=${timings.size} total_ms=${total / 1000.0} " +
                "p50_ms=${percentile(timings, 50)} p99_ms=${percentile(timings, 99)} " +
                "top=\"${top?.name ?: ""}\" kind=\"${top?.kind ?: ""}\" id=\"${top?.id ?: ""}\""
        log(summary)
        return Outcome.Success(CommandResult(summary))
    }

    private suspend fun runCancelled(
        access: PlacesAccess,
        selection: Selection,
        cancellation: CancellationHandle,
    ): Outcome<CommandResult<String>> {
        val started = System.nanoTime()
        val outcome =
            access.search(
                indexName = selection.index,
                query = selection.query,
                context = SearchContext(position = selection.position),
                limit = selection.limit,
                cancellation = cancellation,
            )
        val elapsed = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started)
        return when (outcome) {
            is Outcome.Failure -> {
                if (outcome.error != AppError.Cancelled) {
                    log("cancelled query failed with ${outcome.error}")
                    return outcome
                }
                val summary = "cancelled=true hits=0 ms=${elapsed / 1000.0}"
                log("query=\"${selection.query}\" $summary")
                Outcome.Success(CommandResult(summary))
            }

            is Outcome.Success -> {
                log("cancelled query returned ${outcome.value.size} hits")
                Outcome.Success(
                    CommandResult("cancelled=false hits=${outcome.value.size} ms=${elapsed / 1000.0}"),
                )
            }
        }
    }

    private fun percentile(
        timings: List<Long>,
        percent: Int,
    ): Double {
        if (timings.isEmpty()) {
            return 0.0
        }
        val sorted = timings.sorted()
        val rank = (percent * (sorted.size - 1)) / 100
        return sorted[rank] / 1000.0
    }

    private fun parse(params: JsonElement): Selection? {
        val objectValue =
            when (params) {
                is JsonNull -> JsonObject(emptyMap())
                is JsonObject -> params
                else -> return null
            }
        val query = (objectValue["query"] as? JsonPrimitive)?.contentOrNull?.trim() ?: return null
        if (query.isEmpty()) {
            return null
        }
        val index = (objectValue["index"] as? JsonPrimitive)?.contentOrNull ?: DEFAULT_INDEX
        val limit =
            (objectValue["limit"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_LIMIT
        if (limit !in 1..MAX_SEARCH_RESULTS) {
            return null
        }
        val position =
            (objectValue["position"] as? JsonObject)?.let { point ->
                val latitude = (point["lat"] as? JsonPrimitive)?.doubleOrNull ?: return null
                val longitude = (point["lon"] as? JsonPrimitive)?.doubleOrNull ?: return null
                GeoPoint(latitude, longitude)
            }
        val cancelled = (objectValue["cancel"] as? JsonPrimitive)?.booleanOrNull ?: false
        return Selection(query, index, limit, position, cancelled)
    }

    private data class Selection(
        val query: String,
        val index: String,
        val limit: Int,
        val position: GeoPoint?,
        val cancelled: Boolean,
    )
}

internal fun searchCommands(places: () -> PlacesAccess): List<Command<*, *>> = listOf(SearchQueryCommand(places))
