package nl.r1a.grassmap.plugins.downloads.ui

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.DownloadsState
import nl.r1a.grassmap.plugin.api.model.MobileDataPolicy

/**
 * The downloads commands (T-0504). Each returns the published screen state, so
 * the control channel sees exactly what the screen draws.
 *
 * The transfer commands run on the IO dispatcher: a download that starts, or a
 * cancel that waits for the work directory to go, may take the length of a
 * package rather than a frame. The set-server command takes the token as a
 * parameter and never returns it.
 */
private val TRANSFER_POLICY: CommandPolicy =
    CommandPolicy(
        undoable = false,
        allowlistedForControl = true,
        dispatcher = CommandDispatcher.IO,
    )

private val VIEW_POLICY: CommandPolicy =
    CommandPolicy(
        undoable = false,
        allowlistedForControl = false,
        dispatcher = CommandDispatcher.DEFAULT,
    )

/** Fetches the catalogue and re-reads what is on the phone. */
class DownloadsRefreshCommand(
    private val session: DownloadsUiSession,
) : Command<JsonElement, DownloadsState>(
        id = DownloadsUiContract.refresh,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = DownloadsState.serializer(),
        doc = "Fetches the map catalogue again and re-reads the installed packages.",
        policy = TRANSFER_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<DownloadsState>> = session.result(session.refresh())
}

/** Queues one region's package. Parameter: region. */
class DownloadRegionCommand(
    private val session: DownloadsUiSession,
) : Command<JsonElement, DownloadsState>(
        id = DownloadsUiContract.downloadRegion,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = DownloadsState.serializer(),
        doc = "Queues one region's package and returns the screen state. Parameter: region.",
        policy = TRANSFER_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<DownloadsState>> {
        val region = params.text(PARAM_REGION) ?: return Outcome.Failure(AppError.InvalidInput)
        return session.result(session.download(region))
    }
}

/** Cancels one region's download once its work directory is gone. Parameter: region. */
class CancelRegionDownloadCommand(
    private val session: DownloadsUiSession,
) : Command<JsonElement, DownloadsState>(
        id = DownloadsUiContract.cancelRegion,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = DownloadsState.serializer(),
        doc = "Cancels a region's download and returns the state once its work directory is gone. Parameter: region.",
        policy = TRANSFER_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<DownloadsState>> {
        val region = params.text(PARAM_REGION) ?: return Outcome.Failure(AppError.InvalidInput)
        return session.result(session.cancel(region))
    }
}

/** Removes an installed region. Parameter: region. */
class RemoveRegionCommand(
    private val session: DownloadsUiSession,
) : Command<JsonElement, DownloadsState>(
        id = DownloadsUiContract.removeRegion,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = DownloadsState.serializer(),
        doc = "Removes an installed region from the phone. Parameter: region.",
        policy = TRANSFER_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<DownloadsState>> {
        val region = params.text(PARAM_REGION) ?: return Outcome.Failure(AppError.InvalidInput)
        return session.result(session.remove(region))
    }
}

/**
 * Opens one region's sheet. Parameter: region; an empty region closes the sheet
 * and a missing parameter is invalid input.
 */
class SelectRegionCommand(
    private val session: DownloadsUiSession,
) : Command<JsonElement, DownloadsState>(
        id = DownloadsUiContract.selectRegion,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = DownloadsState.serializer(),
        doc = "Opens the sheet of one region, or closes it with an empty region. Parameter: region.",
        policy = VIEW_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<DownloadsState>> {
        val region = params.rawText(PARAM_REGION) ?: return Outcome.Failure(AppError.InvalidInput)
        return session.result(session.select(region))
    }
}

/**
 * Sets the server and its optional token. Parameters: url, and optionally token.
 *
 * `token` may be absent or empty, which clears the stored token: a tokenless
 * server needs no header at all. The value is passed to the store and is never
 * echoed in a result, a state or a log line.
 */
class SetMapServerCommand(
    private val session: DownloadsUiSession,
) : Command<JsonElement, DownloadsState>(
        id = DownloadsUiContract.setServer,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = DownloadsState.serializer(),
        doc =
            "Sets the map server and its optional download token, then fetches the catalogue. " +
                "Parameters: url, and optionally token; an empty token clears it.",
        policy = TRANSFER_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<DownloadsState>> {
        val url = params.text(PARAM_URL) ?: return Outcome.Failure(AppError.InvalidInput)
        val token = params.rawText(PARAM_TOKEN)?.trim()?.takeIf { it.isNotEmpty() }
        return session.result(session.setServer(url, token))
    }
}

/** Sets whether downloads may use mobile data. Parameter: policy, `wifi_only` or `any`. */
class SetMobileDataPolicyCommand(
    private val session: DownloadsUiSession,
) : Command<JsonElement, DownloadsState>(
        id = DownloadsUiContract.setMobileData,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = DownloadsState.serializer(),
        doc = "Sets the mobile-data download policy. Parameter: policy, wifi_only or any.",
        policy = TRANSFER_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<DownloadsState>> {
        val policy =
            when (params.text(PARAM_POLICY)) {
                POLICY_WIFI_ONLY -> MobileDataPolicy.WIFI_ONLY
                POLICY_ANY -> MobileDataPolicy.ANY
                else -> return Outcome.Failure(AppError.InvalidInput)
            }
        return session.result(session.setMobileData(policy))
    }
}

/** Lets the downloads that wait for Wi-Fi run on mobile data now. */
class UseMobileDataNowCommand(
    private val session: DownloadsUiSession,
) : Command<JsonElement, DownloadsState>(
        id = DownloadsUiContract.useMobileDataNow,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = DownloadsState.serializer(),
        doc = "Lets the downloads that wait for Wi-Fi continue on mobile data until the queue is empty.",
        policy = TRANSFER_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<DownloadsState>> = session.result(session.useMobileDataNow())
}

private fun DownloadsUiSession.result(outcome: Outcome<DownloadsState>): Outcome<CommandResult<DownloadsState>> =
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

/** A string parameter that may be empty, such as a cleared token. */
private fun JsonElement.rawText(key: String): String? {
    val json = this as? JsonObject ?: return null
    return (json[key] as? JsonPrimitive)
        ?.takeIf { primitive -> primitive.isString }
        ?.contentOrNull
}

private const val PARAM_REGION = "region"
private const val PARAM_URL = "url"
private const val PARAM_TOKEN = "token"
private const val PARAM_POLICY = "policy"
private const val POLICY_WIFI_ONLY = "wifi_only"
private const val POLICY_ANY = "any"
