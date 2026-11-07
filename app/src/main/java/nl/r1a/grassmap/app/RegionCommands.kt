package nl.r1a.grassmap.app

import android.util.Log
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import nl.r1a.grassmap.core.services.NetworkSwitches
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.regions.BearerToken
import nl.r1a.grassmap.regions.DownloadPolicy
import nl.r1a.grassmap.regions.InstalledRegion
import nl.r1a.grassmap.regions.NetworkAccess
import nl.r1a.grassmap.regions.RegionDownloader
import nl.r1a.grassmap.regions.RegionRequest

/** The debug trigger that proves the regions seam. */
internal val REGION_DOWNLOAD_COMMAND: CommandId = CommandId("core.region.download_test")

private const val TAG = "GrassMapsRegions"

/**
 * Downloads one region package through the real downloader and reports the
 * verification and install. The caller supplies the package URL, the region and
 * the version; a token is optional so the 401 path is reachable.
 *
 * The request goes through the production [PrivacyNetworkGate] path unless
 * `bypass` is true. The bypass exists only for this control-channel trigger and
 * logs itself on every call; a release build has no control channel, so there
 * is no path to this command at all.
 */
internal class RegionDownloadTestCommand(
    private val downloader: () -> RegionDownloader?,
    private val network: NetworkAccess,
    private val log: (String) -> Unit = { message -> Log.i(TAG, message) },
) : Command<JsonElement, String>(
        id = REGION_DOWNLOAD_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Downloads, verifies and atomically installs a region package from a URL.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val request = parse(params) ?: return Outcome.Failure(AppError.InvalidInput)
        val downloader = downloader() ?: return Outcome.Failure(AppError.NotReady)
        val token =
            when (val parsed = request.token?.let { BearerToken.of(it) }) {
                null -> null
                is Outcome.Success -> parsed.value
                is Outcome.Failure -> return parsed
            }
        val policy =
            DownloadPolicy(
                switch = NetworkSwitches.regionUpdates,
                allowCleartextHttp = true,
            )
        log("request region=${request.region} version=${request.version} cleartext=permitted(debug command)")
        val access = if (request.bypass) DebugNetworkAccess(log) else network
        val outcome =
            downloader.download(
                request =
                    RegionRequest(
                        url = request.url,
                        region = request.region,
                        version = request.version,
                        token = token,
                    ),
                policy = policy,
                access = access,
                cancellation = CancellationHandle.None,
            )
        return when (outcome) {
            is Outcome.Failure -> {
                log("download_test region=${request.region} version=${request.version} failed=${outcome.error}")
                outcome
            }

            is Outcome.Success -> {
                Outcome.Success(CommandResult(summary(outcome.value)))
            }
        }
    }

    private fun summary(installed: InstalledRegion): String {
        val manifest = installed.manifest
        return "region=${manifest.region} version=${manifest.version} " +
            "files=${manifest.components.size} bytes=${installed.verifiedBytes} " +
            "current=${installed.currentVersion} reused=${installed.reusedExisting} " +
            "path=${installed.directory.absolutePath}"
    }

    private fun parse(params: JsonElement): ParsedRequest? {
        val json = params as? JsonObject ?: return null
        val url = (json["url"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val region = (json["region"] as? JsonPrimitive)?.contentOrNull ?: return null
        val version = (json["version"] as? JsonPrimitive)?.contentOrNull ?: return null
        val token = (json["token"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val bypass = (json["bypass"] as? JsonPrimitive)?.booleanOrNull ?: false
        return ParsedRequest(url, region, version, token, bypass)
    }

    private data class ParsedRequest(
        val url: String,
        val region: String,
        val version: String,
        val token: String?,
        val bypass: Boolean,
    )
}

/** A debug-only stand-in for the gate; it names the switch it bypasses. */
private class DebugNetworkAccess(
    private val log: (String) -> Unit,
) : NetworkAccess {
    override suspend fun <T> request(
        switch: SwitchId,
        block: suspend () -> Outcome<T>,
    ): Outcome<T> {
        log("network policy=debug-bypass switch=$switch")
        return block()
    }
}

internal fun regionCommands(
    downloader: () -> RegionDownloader?,
    network: NetworkAccess,
): List<Command<*, *>> = listOf(RegionDownloadTestCommand(downloader, network))
