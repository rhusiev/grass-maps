package nl.r1a.grassmap.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.MapTrack
import nl.r1a.grassmap.plugin.api.model.TrackStyle
import nl.r1a.grassmap.plugins.track.layer.TrackLayer

/** The debug-only command the T-0415 goldens use to draw fixed tracks. */
internal val SET_TRACKS_COMMAND: CommandId = CommandId("core.map.set_tracks")

/**
 * Draws a fixed track set through the production [TrackLayer], or clears it
 * with `{"tracks": null}`.
 *
 * The golden fixture needs two tracks in two colours, and one of them absent,
 * on a fixture map with no imported library. The input is the layer's own
 * `MapTrack` shape in JSON, so the capture exercises the same `MapHost.setTracks`
 * path and the same colour mapping the product uses; the fixture, not this
 * command, decides which tracks are visible.
 */
internal class SetTracksCommand(
    private val session: MapSessionHolder,
) : Command<JsonElement, String>(
        id = SET_TRACKS_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Draws tracks from a JSON list, or clears them with a null list.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.MAIN,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val element = tracksElement(params) ?: return Outcome.Failure(AppError.InvalidInput)
        val tracks =
            when (element) {
                JsonNull -> {
                    emptyList()
                }

                is JsonArray -> {
                    element.mapNotNull(::decodeTrack)
                }

                else -> {
                    return Outcome.Failure(AppError.InvalidInput)
                }
            }
        val host = session.hostOrNull() ?: return Outcome.Failure(AppError.NotReady)
        val layer = TrackLayer(MutableStateFlow(tracks), { host })
        return when (val drawn = layer.refresh()) {
            is Outcome.Failure -> drawn
            is Outcome.Success -> Outcome.Success(CommandResult("tracks=${tracks.size}"))
        }
    }

    private fun decodeTrack(element: JsonElement): MapTrack? {
        val json = element as? JsonObject ?: return null
        val id = json.string("id")?.takeIf { it.isNotBlank() } ?: return null
        val colour = json.string("colour")?.takeIf { it.isNotBlank() } ?: TrackStyle.DEFAULT_COLOUR
        val width = (json["widthDp"] as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: TrackStyle.DEFAULT_WIDTH_DP
        val style = TrackStyle(colour = colour, widthDp = width).takeIf { it.isValid() } ?: return null
        val segments = mutableListOf<List<GeoPoint>>()
        for (segment in json["segments"] as? JsonArray ?: return null) {
            val points =
                (segment as? JsonArray)?.mapNotNull { point ->
                    val coordinates = point as? JsonObject ?: return@mapNotNull null
                    val latitude = (coordinates["lat"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val longitude = (coordinates["lon"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    GeoPoint(latitude = latitude, longitude = longitude)
                } ?: return null
            segments += points
        }
        return MapTrack(id = id, name = json.string("name"), style = style, segments = segments)
    }

    private fun tracksElement(params: JsonElement): JsonElement? =
        when (params) {
            JsonNull -> JsonNull
            is JsonObject -> params["tracks"] ?: JsonNull
            else -> null
        }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
