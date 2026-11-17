package nl.r1a.grassmap.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.DownloadsData
import nl.r1a.grassmap.plugin.api.model.InstalledPack
import nl.r1a.grassmap.plugin.api.model.RegionOutline
import nl.r1a.grassmap.plugins.region.outlines.RegionOutlinesLayer

/** The debug-only command the T-0506 golden uses to highlight fixed regions. */
internal val SET_REGION_HIGHLIGHTS_COMMAND: CommandId = CommandId("core.map.set_region_highlights")

/**
 * Highlights a fixed set of regions through the production
 * [RegionOutlinesLayer], or clears them with `{"regions": null}`.
 *
 * The input is the downloads data's own `RegionOutline` shape in JSON. Each
 * outline is marked installed, so the capture runs the layer's real filter
 * and `MapHost.setRegionHighlights`, and the fixture decides which regions
 * the golden shows.
 */
internal class SetRegionHighlightsCommand(
    private val session: MapSessionHolder,
) : Command<JsonElement, String>(
        id = SET_REGION_HIGHLIGHTS_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Highlights regions from a JSON outline list, or clears them with a null list.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.MAIN,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val outlines = decode(params) ?: return Outcome.Failure(AppError.InvalidInput)
        val host = session.hostOrNull() ?: return Outcome.Failure(AppError.NotReady)
        val data =
            DownloadsData(
                installed = outlines.map { InstalledPack(region = it.region, version = FIXTURE_VERSION) },
                outlines = outlines,
            )
        return when (val drawn = RegionOutlinesLayer(MutableStateFlow(data), { host }).refresh()) {
            is Outcome.Failure -> drawn
            is Outcome.Success -> Outcome.Success(CommandResult("regions=${outlines.size}"))
        }
    }

    private fun decode(params: JsonElement): List<RegionOutline>? {
        val element = (params as? JsonObject)?.get("regions") ?: JsonNull
        if (element == JsonNull) return emptyList()
        return try {
            Json.decodeFromJsonElement(ListSerializer(RegionOutline.serializer()), element)
        } catch (_: SerializationException) {
            null
        }
    }

    private companion object {
        const val FIXTURE_VERSION = "fixture"
    }
}
