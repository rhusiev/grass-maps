package nl.r1a.grassmap.app

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.ui.shell.PlaceCardContent
import nl.r1a.grassmap.ui.shell.placeCardContent

/** The debug-only commands that open and close the place card. */
internal val SHOW_PLACE_CARD_COMMAND: CommandId = CommandId("core.place.card_show")
internal val HIDE_PLACE_CARD_COMMAND: CommandId = CommandId("core.place.card_hide")

private const val DEFAULT_INDEX = "lviv"

internal fun placeCardCommands(container: () -> AppContainer): List<Command<*, *>> =
    listOf(ShowPlaceCardCommand(container), HidePlaceCardCommand())

/**
 * Loads one place through the provider seam and shows its card. Only a debug
 * build has this command: the search screen (T-0203) will open the card, and
 * this is the device entry point until then.
 */
internal class ShowPlaceCardCommand(
    private val container: () -> AppContainer,
) : Command<JsonElement, String>(
        id = SHOW_PLACE_CARD_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Opens the place card for one index place id and returns its resolved rows.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val request = parse(params) ?: return Outcome.Failure(AppError.InvalidInput)
        val active = container()
        val access =
            PlaceCardAccess(
                details = { index, placeId, cancellation -> active.places.details(index, placeId, cancellation) },
                providers = { active.providerRegistry },
            )
        return when (val outcome = access.card(request.index, request.placeId, CancellationHandle.None)) {
            is Outcome.Failure -> {
                outcome
            }

            is Outcome.Success -> {
                PlaceCardSession.show(outcome.value)
                Outcome.Success(CommandResult(describe(placeCardContent(outcome.value, active.strings))))
            }
        }
    }

    private fun parse(params: JsonElement): Request? {
        val json = params as? JsonObject ?: return null
        val placeId = (json["id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val index =
            (json["index"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_INDEX
        return Request(index, placeId)
    }

    private data class Request(
        val index: String,
        val placeId: String,
    )
}

/** Closes the card; it is safe when nothing is open. */
internal class HidePlaceCardCommand :
    Command<JsonElement, String>(
        id = HIDE_PLACE_CARD_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Closes the place card.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        PlaceCardSession.hide()
        return Outcome.Success(CommandResult("hidden=true"))
    }
}

/** The same rows the card draws, with the active language applied. */
private fun describe(content: PlaceCardContent): String =
    buildString {
        content.name?.let { appendLine("name=$it") }
        content.kind?.let { appendLine("kind=$it") }
        content.address?.let { appendLine("address=$it") }
        content.openingHours?.let { appendLine("${it.label}=${it.value}") }
        content.openingState?.let { appendLine("${it.label}=${it.value}") }
        content.contacts.forEach { appendLine("contact: ${it.label}=${it.value}") }
        content.accessibility.forEach { appendLine("accessibility: ${it.label}=${it.value}") }
        content.attributes.forEach { appendLine("details: ${it.label}=${it.value}") }
        append("rows=")
        append(
            listOf(content.contacts, content.accessibility, content.attributes).sumOf { it.size } +
                (if (content.openingHours != null) 1 else 0) +
                (if (content.openingState != null) 1 else 0),
        )
    }
