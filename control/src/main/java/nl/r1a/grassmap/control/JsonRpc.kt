package nl.r1a.grassmap.control

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome

/** One JSON-RPC 2.0 request. A request without an `id` is a notification. */
data class RpcRequest(
    val id: JsonElement?,
    val method: String,
    val params: JsonObject,
)

/** A JSON-RPC 2.0 error object. [data] carries the typed refusal kind. */
data class RpcError(
    val code: Int,
    val message: String,
    val data: JsonObject = JsonObject(emptyMap()),
)

/** JSON-RPC 2.0 and control-channel error codes. */
object RpcCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL = -32603

    /** Defined by the channel, in the implementation-reserved range. */
    const val UNAUTHORIZED = -32001
    const val NOT_FOUND = -32002
    const val SWITCH_OFF = -32003
    const val DENIED = -32004
}

/**
 * Encodes and parses JSON-RPC 2.0 frames. Nothing here touches a socket, so the
 * framing and the envelope are tested without Android.
 */
object JsonRpcCodec {
    const val VERSION: String = "2.0"
    const val NOTIFICATION_STREAM_DATA: String = "control.stream.data"

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): Outcome<RpcRequest> {
        val element =
            try {
                json.parseToJsonElement(text)
            } catch (exception: SerializationException) {
                return Outcome.Failure(AppError.InvalidInput)
            } catch (exception: IllegalArgumentException) {
                return Outcome.Failure(AppError.InvalidInput)
            }
        val objectValue = element as? JsonObject ?: return Outcome.Failure(AppError.InvalidInput)
        if (objectValue["jsonrpc"]?.jsonPrimitive?.contentOrNull != VERSION) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val method =
            objectValue["method"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return Outcome.Failure(AppError.InvalidInput)
        val id = objectValue["id"]?.takeUnless { it is JsonNull }
        val params = objectValue["params"] as? JsonObject ?: JsonObject(emptyMap())
        return Outcome.Success(RpcRequest(id = id, method = method, params = params))
    }

    fun response(
        id: JsonElement?,
        result: JsonElement,
    ): String =
        buildJsonObject {
            put("jsonrpc", VERSION)
            put("id", id ?: JsonNull)
            put("result", result)
        }.toString()

    fun error(
        id: JsonElement?,
        error: RpcError,
    ): String {
        val errorObject = error.toJson()
        return buildJsonObject {
            put("jsonrpc", VERSION)
            put("id", id ?: JsonNull)
            put("error", errorObject)
        }.toString()
    }

    fun notification(
        method: String,
        params: JsonObject,
    ): String =
        buildJsonObject {
            put("jsonrpc", VERSION)
            put("method", method)
            put("params", params)
        }.toString()

    fun RpcError.toJson(): JsonObject =
        buildJsonObject {
            put("code", code)
            put("message", message)
            if (data.isNotEmpty()) {
                put("data", data)
            }
        }

    fun refusal(
        code: Int,
        kind: String,
        message: String,
        extra: Map<String, JsonElement> = emptyMap(),
    ): RpcError {
        val data =
            buildJsonObject {
                put("kind", kind)
                for ((key, value) in extra) {
                    put(key, value)
                }
            }
        return RpcError(code = code, message = message, data = data)
    }

    /** Maps a typed app error to a typed RPC error. No user text and no secrets. */
    fun fromAppError(error: AppError): RpcError =
        when (error) {
            AppError.InvalidInput -> {
                refusal(RpcCodes.INVALID_PARAMS, "invalid_input", "invalid params")
            }

            AppError.NotFound -> {
                refusal(RpcCodes.NOT_FOUND, "not_found", "not found")
            }

            AppError.NotReady -> {
                refusal(RpcCodes.INTERNAL, "not_ready", "not ready")
            }

            AppError.PermissionDenied -> {
                refusal(RpcCodes.DENIED, "permission_denied", "permission denied")
            }

            is AppError.SwitchOff -> {
                refusal(
                    RpcCodes.SWITCH_OFF,
                    "switch_off",
                    "switch off",
                    mapOf("switch" to JsonPrimitive(error.switch.value)),
                )
            }

            AppError.Network -> {
                refusal(RpcCodes.INTERNAL, "network", "network failure")
            }

            is AppError.Server -> {
                refusal(
                    RpcCodes.INTERNAL,
                    "server",
                    "server error",
                    mapOf("status" to JsonPrimitive(error.status)),
                )
            }

            AppError.CorruptData -> {
                refusal(RpcCodes.INTERNAL, "corrupt_data", "corrupt data")
            }

            AppError.Unsupported -> {
                refusal(RpcCodes.INTERNAL, "unsupported", "unsupported")
            }

            AppError.Cancelled -> {
                refusal(RpcCodes.INTERNAL, "cancelled", "cancelled")
            }

            is AppError.PluginFailed -> {
                refusal(
                    RpcCodes.INTERNAL,
                    "plugin_failed",
                    "plugin failed",
                    mapOf("plugin" to JsonPrimitive(error.pluginId.value)),
                )
            }

            is AppError.Internal -> {
                refusal(
                    RpcCodes.INTERNAL,
                    "internal",
                    "internal error",
                    mapOf("id" to JsonPrimitive(error.id)),
                )
            }
        }
}

/** Reads a string member, or null when it is missing or not a primitive string. */
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** Reads a long member, or null when it is missing or not an integral primitive. */
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

/** Reads a boolean member, or null when it is missing or not a boolean primitive. */
internal fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
