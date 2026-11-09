package nl.r1a.grassmap.plugins.share

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.builtins.serializer
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
import nl.r1a.grassmap.plugin.api.error.map
import nl.r1a.grassmap.plugin.api.host.PlatformActions
import nl.r1a.grassmap.plugin.api.host.PluginServices
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.OsmType
import java.util.Locale

/**
 * The share commands (T-0210).
 *
 * They run on Main because all three effects touch the platform UI: the
 * clipboard, the chooser and the `ACTION_VIEW` resolution. Each returns the
 * exact text or URI it handed to [PlatformActions], so the control channel can
 * report what was copied or shared without reading the clipboard itself.
 */
private val SHARE_POLICY: CommandPolicy =
    CommandPolicy(
        undoable = false,
        allowlistedForControl = true,
        dispatcher = CommandDispatcher.MAIN,
    )

/** Copies one coordinate notation of a point. Parameter: `format` (decimal|dms|ddm). */
class CopyCoordinatesCommand(
    private val actions: PlatformActions,
) : Command<JsonElement, String>(
        id = ShareContract.copyCoordinates,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Copies a point's coordinates. Parameters: latitude, longitude, and format (decimal, dms or ddm).",
        policy = SHARE_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val json = params.objectOrNull() ?: return Outcome.Failure(AppError.InvalidInput)
        val point = json.pointOrNull() ?: return Outcome.Failure(AppError.InvalidInput)
        val format = coordinateFormat(json.text(PARAM_FORMAT)) ?: return Outcome.Failure(AppError.InvalidInput)
        val text = CoordinateFormats.format(point, format)
        return actions.copyToClipboard(text).map { CommandResult(text) }
    }

    private companion object {
        const val PARAM_FORMAT = "format"
    }
}

/** Shares a place. Parameters: name, latitude, longitude, osmType and osmId. */
class SharePlaceCommand(
    private val actions: PlatformActions,
) : Command<JsonElement, String>(
        id = ShareContract.sharePlace,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Shares a place. Parameters: name, latitude, longitude, and the optional osmType and osmId.",
        policy = SHARE_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val json = params.objectOrNull() ?: return Outcome.Failure(AppError.InvalidInput)
        val point = json.pointOrNull() ?: return Outcome.Failure(AppError.InvalidInput)
        val rawType = json.text(PARAM_OSM_TYPE)
        val type = rawType?.let { name -> OsmType.entries.firstOrNull { it.name == name.uppercase(Locale.ROOT) } }
        if (rawType != null && type == null) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val identity = ShareLinks.identityOf(type, json.long(PARAM_OSM_ID))
        val name = json.text(PARAM_NAME)
        val message = ShareCard.placeMessage(name, identity, point)
        return actions.shareText(message, name).map { CommandResult(message) }
    }

    private companion object {
        const val PARAM_NAME = "name"
        const val PARAM_OSM_ID = "osmId"
    }
}

/**
 * Shares the current location: the newest fix when there is one, else the map
 * centre. Optional `latitude`/`longitude` override both, which is how the card
 * and the control channel can name an exact point.
 */
class ShareLocationCommand(
    private val actions: PlatformActions,
    private val services: PluginServices,
) : Command<JsonElement, String>(
        id = ShareContract.shareLocation,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Shares the current location or an explicit point. Parameters: optional latitude and longitude.",
        policy = SHARE_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val point = params.objectOrNull()?.pointOrNull() ?: currentPoint() ?: return Outcome.Failure(AppError.NotReady)
        val message = ShareCard.locationMessage(point)
        return actions.shareText(message).map { CommandResult(message) }
    }

    /**
     * The fix first and the map centre second, as the task states. Both flows
     * emit their newest value immediately by contract, so one value each is
     * enough and the collection stops with the first emission.
     */
    private suspend fun currentPoint(): GeoPoint? =
        services.location.fixes
            .firstOrNull()
            ?.position ?: services.camera.firstOrNull()?.center
}

/** Opens a point in another app with its `geo:` URI. Parameters: latitude, longitude, label. */
class OpenPointCommand(
    private val actions: PlatformActions,
) : Command<JsonElement, String>(
        id = ShareContract.openPoint,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Opens a point in another app. Parameters: latitude, longitude, and an optional label.",
        policy = SHARE_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val json = params.objectOrNull() ?: return Outcome.Failure(AppError.InvalidInput)
        val point = json.pointOrNull() ?: return Outcome.Failure(AppError.InvalidInput)
        val uri = ShareLinks.geoUri(point, json.text(PARAM_LABEL))
        return actions.openUri(uri).map { CommandResult(uri) }
    }

    private companion object {
        const val PARAM_LABEL = "label"
    }
}

private fun JsonElement.objectOrNull(): JsonObject? = this as? JsonObject

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun JsonObject.long(key: String): Long? = text(key)?.toLongOrNull()

private fun JsonObject.double(key: String): Double? = text(key)?.toDoubleOrNull()

private fun JsonObject.pointOrNull(): GeoPoint? {
    val latitude = double(PARAM_LATITUDE) ?: double(PARAM_LAT) ?: return null
    val longitude = double(PARAM_LONGITUDE) ?: double(PARAM_LON) ?: return null
    return GeoPoint(latitude, longitude).takeIf { it.isFinite() }
}

private fun coordinateFormat(raw: String?): CoordinateFormat? =
    when (raw?.lowercase(Locale.ROOT)) {
        null, "decimal" -> CoordinateFormat.DECIMAL
        "dms" -> CoordinateFormat.DMS
        "ddm" -> CoordinateFormat.DDM
        else -> null
    }

private const val PARAM_LATITUDE = "latitude"
private const val PARAM_LONGITUDE = "longitude"
private const val PARAM_LAT = "lat"
private const val PARAM_LON = "lon"
private const val PARAM_OSM_TYPE = "osmType"
