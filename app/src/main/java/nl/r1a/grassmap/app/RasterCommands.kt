package nl.r1a.grassmap.app

import android.util.Log
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugins.raster.RasterPlacement
import nl.r1a.grassmap.plugins.raster.RasterPresets
import nl.r1a.grassmap.plugins.raster.RasterSource
import nl.r1a.grassmap.plugins.raster.RasterSources
import nl.r1a.grassmap.plugins.raster.RasterTransparency

/** The debug trigger the T-0514 goldens use to draw a tile source. */
internal val RASTER_SHOW_COMMAND: CommandId = CommandId("core.map.show_raster")

private const val TAG = "GrassMapsRaster"

/**
 * Shows one tile URL template at a placement and transparency, or clears the
 * layer with `{"tiles": null}`.
 *
 * It exists for the map goldens: the `raster` profile serves its committed tile
 * fixture from a loopback server and draws it through this command, so the
 * capture exercises the same custom-source path, the same privacy switch and
 * the same style seam the layers panel uses. It is not product UI.
 *
 * The command bus can invoke it in any build, so `allowlistedForControl` alone
 * is not treated as the gate: a build that is not debuggable refuses before the
 * switch or the tile source changes, which the composition root reports from
 * `ApplicationInfo.FLAG_DEBUGGABLE`.
 */
internal class RasterShowCommand(
    private val settings: () -> RasterLayerSettings,
    private val enableOnlineTiles: suspend (Boolean) -> Outcome<Unit>,
    private val probe: suspend (RasterSource) -> Outcome<Unit>,
    private val debuggable: () -> Boolean = { false },
    private val log: (String) -> Unit = { message -> Log.i(TAG, message) },
) : Command<JsonElement, String>(
        id = RASTER_SHOW_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Shows a raster tile URL template as an overlay or an underlay, or clears it.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        if (!debuggable()) {
            log("show_raster refused: build is not debuggable")
            return Outcome.Failure(AppError.PermissionDenied)
        }
        val request = parse(params) ?: return Outcome.Failure(AppError.InvalidInput)
        val settings = settings()
        val template = request.tiles
        if (template == null) {
            return when (val disabled = enableOnlineTiles(false)) {
                is Outcome.Failure -> disabled
                is Outcome.Success -> Outcome.Success(CommandResult("raster=off"))
            }
        }
        // A template the product would refuse never reaches the switch, so a
        // mistyped fixture leaves the previous layer exactly as it was. The
        // probe fetches the source's lowest zoom through the same template.
        val parsed = RasterSources.custom(template, minZoom = request.minZoom, maxZoom = request.maxZoom)
        if (parsed is Outcome.Failure) {
            log("show_raster invalid tile template")
            return Outcome.Failure(AppError.InvalidInput)
        }
        when (val enabled = enableOnlineTiles(true)) {
            is Outcome.Failure -> return enabled
            is Outcome.Success -> Unit
        }
        if (settings.current().source.tileUrlTemplate != template) {
            val applied =
                settings.useCustom(
                    template = template,
                    minZoom = request.minZoom,
                    maxZoom = request.maxZoom,
                    probe = probe,
                )
            when (applied) {
                is Outcome.Failure -> return applied
                is Outcome.Success -> Unit
            }
        }
        when (val placed = settings.setPlacement(request.placement)) {
            is Outcome.Failure -> return placed
            is Outcome.Success -> Unit
        }
        when (val transparency = settings.setTransparencyPercent(request.percent)) {
            is Outcome.Failure -> return transparency
            is Outcome.Success -> Unit
        }
        val layer = settings.current()
        return Outcome.Success(
            CommandResult(
                "raster=${layer.source.tileUrlTemplate} placement=${layer.placement} " +
                    "percent=${layer.transparencyPercent}",
            ),
        )
    }

    private fun parse(params: JsonElement): Request? {
        val json = params as? JsonObject ?: return null
        val tiles =
            when (val element = json["tiles"]) {
                null, JsonNull -> null
                is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }
                else -> return null
            }
        val placement =
            when (val text = (json["placement"] as? JsonPrimitive)?.contentOrNull) {
                null -> RasterPlacement.Overlay
                PLACEMENT_UNDERLAY -> RasterPlacement.Underlay
                PLACEMENT_OVERLAY -> RasterPlacement.Overlay
                else -> return null
            }
        val percent =
            when (val element = json["percent"]) {
                null, JsonNull -> RasterTransparency.MAX_PERCENT
                is JsonPrimitive -> element.intOrNull ?: return null
                else -> return null
            }
        val minZoom =
            when (val element = json["minZoom"]) {
                null, JsonNull -> 0
                is JsonPrimitive -> element.intOrNull ?: return null
                else -> return null
            }
        val maxZoom =
            when (val element = json["maxZoom"]) {
                null, JsonNull -> RasterPresets.MAX_SUPPORTED_ZOOM
                is JsonPrimitive -> element.intOrNull ?: return null
                else -> return null
            }
        return Request(tiles, placement, percent, minZoom, maxZoom)
    }

    private data class Request(
        val tiles: String?,
        val placement: RasterPlacement,
        val percent: Int,
        val minZoom: Int,
        val maxZoom: Int,
    )

    private companion object {
        const val PLACEMENT_OVERLAY = "overlay"
        const val PLACEMENT_UNDERLAY = "underlay"
    }
}

/** The command set the control channel lists for the raster layer. */
internal fun rasterCommands(
    settings: () -> RasterLayerSettings,
    enableOnlineTiles: suspend (Boolean) -> Outcome<Unit>,
    probe: suspend (RasterSource) -> Outcome<Unit>,
    debuggable: () -> Boolean = { false },
): List<Command<*, *>> = listOf(RasterShowCommand(settings, enableOnlineTiles, probe, debuggable))
