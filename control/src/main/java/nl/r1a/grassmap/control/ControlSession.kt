package nl.r1a.grassmap.control

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * One controller connection: authenticate, then serve JSON-RPC requests and
 * stream notifications until the peer leaves or breaks a framing rule.
 *
 * Every method except `auth` is refused until the bearer token matched. A
 * command only runs when the generated registry marks it allowlisted for
 * control; the bus still enforces the command's own policy afterwards.
 */
class ControlSession(
    private val endpoint: ControlEndpoint,
    private val token: ControlToken,
    private val transport: FrameTransport,
    private val maxFailedAuth: Int = DEFAULT_MAX_FAILED_AUTH,
) {
    private val writeMutex = Mutex()
    private val subscriptions = ConcurrentHashMap<Long, Job>()
    private val nextSubscription = AtomicLong(FIRST_SUBSCRIPTION)
    private var authenticated = false
    private var failedAuth = 0

    suspend fun run() {
        coroutineScope {
            try {
                while (currentCoroutineContext().isActive) {
                    val read = transport.readFrame()
                    val frame =
                        when (read) {
                            FrameRead.EndOfStream -> {
                                break
                            }

                            is FrameRead.Oversized -> {
                                write(errorFrame(null, oversized()))
                                break
                            }

                            is FrameRead.Frame -> {
                                read.text
                            }
                        }
                    if (!handleFrame(frame, this)) {
                        break
                    }
                }
            } finally {
                // Subscriptions are children of this scope. They must be
                // cancelled before it returns, or a live stream keeps the
                // session (and the next client) waiting forever.
                subscriptions.values.forEach { it.cancel() }
                subscriptions.clear()
            }
        }
    }

    private suspend fun handleFrame(
        text: String,
        scope: CoroutineScope,
    ): Boolean {
        val request =
            when (val parsed = JsonRpcCodec.parse(text)) {
                is Outcome.Success -> {
                    parsed.value
                }

                is Outcome.Failure -> {
                    write(errorFrame(null, parseError()))
                    return true
                }
            }
        val reply = dispatch(request, scope)
        if (request.id != null) {
            when (reply) {
                is Reply.Result -> write(JsonRpcCodec.response(request.id, reply.value))
                is Reply.Failure -> write(errorFrame(request.id, reply.error))
            }
        }
        // A notification gets no response, but it must still close the
        // connection when it breaks the failed-auth limit.
        return !(reply is Reply.Failure && reply.closeConnection)
    }

    private suspend fun dispatch(
        request: RpcRequest,
        scope: CoroutineScope,
    ): Reply {
        if (request.method == METHOD_AUTH) {
            return authenticate(request)
        }
        if (!authenticated) {
            failedAuth += 1
            return Reply.Failure(unauthorized(), closeConnection = failedAuth >= maxFailedAuth)
        }
        return try {
            when (request.method) {
                METHOD_LIST_COMMANDS -> {
                    listCommands()
                }

                METHOD_INVOKE_COMMAND -> {
                    invokeCommand(request.params)
                }

                METHOD_SUBSCRIBE -> {
                    subscribe(request.params, scope)
                }

                METHOD_UNSUBSCRIBE -> {
                    unsubscribe(request.params)
                }

                METHOD_SCREENSHOT -> {
                    screenshot()
                }

                else -> {
                    Reply.Failure(
                        JsonRpcCodec.refusal(
                            RpcCodes.METHOD_NOT_FOUND,
                            "method_not_found",
                            "method not found",
                            mapOf("method" to JsonPrimitive(request.method)),
                        ),
                    )
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Reply.Failure(JsonRpcCodec.refusal(RpcCodes.INTERNAL, "handler_failure", "internal error"))
        }
    }

    private fun authenticate(request: RpcRequest): Reply {
        if (authenticated) {
            return Reply.Result(JsonObject(emptyMap()))
        }
        val candidate = request.params.string(TOKEN_PARAM)
        if (candidate != null && token.matches(candidate)) {
            authenticated = true
            failedAuth = 0
            return Reply.Result(JsonObject(emptyMap()))
        }
        failedAuth += 1
        return Reply.Failure(unauthorized(), closeConnection = failedAuth >= maxFailedAuth)
    }

    private fun listCommands(): Reply {
        val commands =
            endpoint.catalog.commands().map { command ->
                buildJsonObject {
                    put("id", command.id.value)
                    put("doc", command.doc)
                    put("plugin", command.plugin.value)
                    put("allowlisted", command.allowlisted)
                    val requiredSwitch = command.requiresSwitch
                    if (requiredSwitch == null) {
                        put("requiresSwitch", JsonNull)
                    } else {
                        put("requiresSwitch", requiredSwitch.value)
                    }
                    put("dispatcher", command.dispatcher.name.lowercase())
                }
            }
        return Reply.Result(buildJsonObject { put("commands", JsonArray(commands)) })
    }

    private suspend fun invokeCommand(params: JsonObject): Reply {
        val commandId = params.string(COMMAND_PARAM) ?: return invalidParams(COMMAND_PARAM)
        val command = endpoint.catalog.command(commandId) ?: return Reply.Failure(unknownCommand(commandId))
        if (!command.allowlisted) {
            return Reply.Failure(notAllowlisted(commandId))
        }
        val commandParams = params[PARAMS_PARAM] ?: JsonNull
        return when (val outcome = endpoint.invoker.invoke(CommandId(commandId), commandParams)) {
            is Outcome.Success -> Reply.Result(outcome.value)
            is Outcome.Failure -> Reply.Failure(JsonRpcCodec.fromAppError(outcome.error))
        }
    }

    private suspend fun subscribe(
        params: JsonObject,
        scope: CoroutineScope,
    ): Reply {
        val streamId = params.string(STREAM_PARAM) ?: return invalidParams(STREAM_PARAM)
        return when (val opened = endpoint.streams.open(StreamId(streamId))) {
            is Outcome.Failure -> {
                Reply.Failure(streamFailure(streamId, opened.error))
            }

            is Outcome.Success -> {
                val subscription = nextSubscription.getAndIncrement()
                val job = scope.launch { forward(subscription, streamId, opened.value) }
                subscriptions[subscription] = job
                job.invokeOnCompletion { subscriptions.remove(subscription) }
                Reply.Result(buildJsonObject { put(SUBSCRIPTION_PARAM, subscription) })
            }
        }
    }

    private suspend fun unsubscribe(params: JsonObject): Reply {
        val subscription = params.long(SUBSCRIPTION_PARAM) ?: return invalidParams(SUBSCRIPTION_PARAM)
        val job = subscriptions.remove(subscription) ?: return Reply.Failure(notSubscribed(subscription))
        job.cancelAndJoin()
        return Reply.Result(JsonObject(emptyMap()))
    }

    private suspend fun screenshot(): Reply {
        val capture =
            endpoint.screenshot
                ?: return Reply.Failure(
                    JsonRpcCodec.refusal(RpcCodes.METHOD_NOT_FOUND, "screenshot_unavailable", "screenshot unavailable"),
                )
        return when (val outcome = capture.capturePng()) {
            is Outcome.Failure -> {
                Reply.Failure(JsonRpcCodec.fromAppError(outcome.error))
            }

            is Outcome.Success -> {
                if (outcome.value.png.isEmpty()) {
                    return Reply.Failure(
                        JsonRpcCodec.refusal(RpcCodes.INTERNAL, "empty_capture", "capture produced no bytes"),
                    )
                }
                Reply.Result(
                    buildJsonObject {
                        put("format", "png")
                        put("source", outcome.value.source)
                        put("base64", Base64.getEncoder().encodeToString(outcome.value.png))
                    },
                )
            }
        }
    }

    private suspend fun forward(
        subscription: Long,
        streamId: String,
        values: Flow<JsonElement>,
    ) {
        try {
            values.collect { value ->
                val params =
                    buildJsonObject {
                        put(SUBSCRIPTION_PARAM, subscription)
                        put(STREAM_PARAM, streamId)
                        put("value", value)
                    }
                val sent = write(JsonRpcCodec.notification(JsonRpcCodec.NOTIFICATION_STREAM_DATA, params))
                if (!sent) {
                    // The peer is gone; a subscription must not keep writing.
                    throw IOException("control write failed")
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: IOException) {
            // A dead peer ends its subscriptions.
        } catch (exception: Exception) {
            // A stream that fails ends its subscription, not the connection.
        }
    }

    private suspend fun write(frame: String): Boolean = writeMutex.withLock { transport.writeFrame(frame) }

    private fun errorFrame(
        id: JsonElement?,
        error: RpcError,
    ): String = JsonRpcCodec.error(id, error)

    private fun parseError(): RpcError = JsonRpcCodec.refusal(RpcCodes.PARSE_ERROR, "parse_error", "malformed JSON-RPC frame")

    private fun oversized(): RpcError = JsonRpcCodec.refusal(RpcCodes.INVALID_REQUEST, "frame_too_large", "frame exceeded the size limit")

    private fun unauthorized(): RpcError = JsonRpcCodec.refusal(RpcCodes.UNAUTHORIZED, "unauthorized", "authentication required")

    private fun unknownCommand(commandId: String): RpcError =
        JsonRpcCodec.refusal(
            RpcCodes.NOT_FOUND,
            "unknown_command",
            "unknown command",
            mapOf(COMMAND_PARAM to JsonPrimitive(commandId)),
        )

    private fun notAllowlisted(commandId: String): RpcError =
        JsonRpcCodec.refusal(
            RpcCodes.DENIED,
            "not_allowlisted",
            "command is not allowlisted for control",
            mapOf(COMMAND_PARAM to JsonPrimitive(commandId)),
        )

    private fun notSubscribed(subscription: Long): RpcError =
        JsonRpcCodec.refusal(
            RpcCodes.NOT_FOUND,
            "not_subscribed",
            "unknown subscription",
            mapOf(SUBSCRIPTION_PARAM to JsonPrimitive(subscription)),
        )

    private fun streamFailure(
        streamId: String,
        error: AppError,
    ): RpcError =
        if (error is AppError.NotFound) {
            JsonRpcCodec.refusal(
                RpcCodes.NOT_FOUND,
                "unknown_stream",
                "unknown stream",
                mapOf(STREAM_PARAM to JsonPrimitive(streamId)),
            )
        } else {
            JsonRpcCodec.fromAppError(error)
        }

    private fun invalidParams(field: String): Reply =
        Reply.Failure(
            JsonRpcCodec.refusal(
                RpcCodes.INVALID_PARAMS,
                "invalid_params",
                "missing or invalid parameter",
                mapOf("field" to JsonPrimitive(field)),
            ),
        )

    private sealed interface Reply {
        data class Result(
            val value: JsonElement,
        ) : Reply

        data class Failure(
            val error: RpcError,
            val closeConnection: Boolean = false,
        ) : Reply
    }

    companion object {
        const val METHOD_AUTH: String = "auth"
        const val METHOD_LIST_COMMANDS: String = "control.commands.list"
        const val METHOD_INVOKE_COMMAND: String = "control.command.invoke"
        const val METHOD_SUBSCRIBE: String = "control.stream.subscribe"
        const val METHOD_UNSUBSCRIBE: String = "control.stream.unsubscribe"
        const val METHOD_SCREENSHOT: String = "devtools.screenshot"

        const val TOKEN_PARAM: String = "token"
        const val COMMAND_PARAM: String = "command"
        const val PARAMS_PARAM: String = "params"
        const val STREAM_PARAM: String = "stream"
        const val SUBSCRIPTION_PARAM: String = "subscription"

        const val DEFAULT_MAX_FAILED_AUTH: Int = 3
        private const val FIRST_SUBSCRIPTION = 1L
    }
}
