package nl.r1a.grassmap.control

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.CommandInvoker

/** An in-memory frame transport, so the session is tested without a socket. */
internal class ChannelFrameTransport : FrameTransport {
    private val incoming = Channel<String>(Channel.UNLIMITED)
    private val outgoing = Channel<String>(Channel.UNLIMITED)

    fun send(text: String) {
        incoming.trySend(text)
    }

    suspend fun next(timeoutMillis: Long = 2_000): String = withTimeout(timeoutMillis) { outgoing.receive() }

    fun pending(): String? = outgoing.tryReceive().getOrNull()

    override suspend fun readFrame(): FrameRead =
        try {
            FrameRead.Frame(incoming.receive())
        } catch (exception: ClosedReceiveChannelException) {
            FrameRead.EndOfStream
        }

    override suspend fun writeFrame(frame: String): Boolean = outgoing.trySend(frame).isSuccess

    override fun close() {
        incoming.close()
        outgoing.close()
    }
}

internal class RecordingInvoker(
    private val reply: Outcome<JsonElement> = Outcome.Success(JsonNull),
) : CommandInvoker {
    val invocations = mutableListOf<Pair<CommandId, JsonElement>>()

    override suspend fun invoke(
        id: CommandId,
        params: JsonElement,
    ): Outcome<JsonElement> {
        invocations += id to params
        return reply
    }

    override suspend fun undo(): Outcome<Unit> = Outcome.Success(Unit)

    override suspend fun redo(): Outcome<Unit> = Outcome.Success(Unit)
}

internal class FakeStreams(
    private val flows: Map<String, Flow<JsonElement>> = emptyMap(),
) : ControlStreamSource {
    override suspend fun open(id: StreamId): Outcome<Flow<JsonElement>> {
        val flow = flows[id.value] ?: return Outcome.Failure(AppError.NotFound)
        return Outcome.Success(flow)
    }
}

internal class ListCatalog(
    private val commands: List<ControlCommand>,
) : ControlCatalog {
    override fun commands(): List<ControlCommand> = commands

    override fun command(id: String): ControlCommand? = commands.firstOrNull { it.id.value == id }
}

internal fun testCommand(
    id: String,
    allowlisted: Boolean = true,
    doc: String = "Test command.",
    dispatcher: CommandDispatcher = CommandDispatcher.DEFAULT,
): ControlCommand =
    ControlCommand(
        id = CommandId(id),
        doc = doc,
        plugin = PluginId("test"),
        allowlisted = allowlisted,
        requiresSwitch = null,
        dispatcher = dispatcher,
    )

internal fun requestFrame(
    id: Long?,
    method: String,
    params: JsonObject = JsonObject(emptyMap()),
): String =
    buildJsonObject {
        put("jsonrpc", "2.0")
        if (id != null) {
            put("id", id)
        }
        put("method", method)
        put("params", params)
    }.toString()

internal class Harness(
    commands: List<ControlCommand> =
        listOf(
            testCommand(id = "test.allowed"),
            testCommand(id = "test.hidden", allowlisted = false),
        ),
    val invoker: RecordingInvoker = RecordingInvoker(),
    streams: ControlStreamSource = FakeStreams(),
    val screenshot: ControlScreenshot? = null,
    val maxFailedAuth: Int = ControlSession.DEFAULT_MAX_FAILED_AUTH,
) {
    val token: ControlToken = ControlTokens.generate()
    val transport = ChannelFrameTransport()
    val session =
        ControlSession(
            endpoint =
                ControlEndpoint(
                    catalog = ListCatalog(commands),
                    invoker = invoker,
                    streams = streams,
                    screenshot = screenshot,
                ),
            token = token,
            transport = transport,
            maxFailedAuth = maxFailedAuth,
        )

    fun rawToken(): String = token.rawValue()

    fun send(
        id: Long?,
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
    ) {
        transport.send(requestFrame(id, method, params))
    }

    suspend fun authenticate(id: Long = AUTH_ID) {
        send(id, ControlSession.METHOD_AUTH, authParams(rawToken()))
        val response = nextObject()
        check(response["error"] == null) { "authentication failed: $response" }
    }

    suspend fun nextObject(): JsonObject = Json.parseToJsonElement(transport.next()).jsonObject

    fun authParams(token: String): JsonObject =
        buildJsonObject {
            put(ControlSession.TOKEN_PARAM, JsonPrimitive(token))
        }

    companion object {
        const val AUTH_ID = 1L
    }
}

internal fun JsonObject.errorCode(): Int =
    this["error"]
        ?.jsonObject
        ?.get("code")
        ?.jsonPrimitive
        ?.int ?: 0

internal fun JsonObject.errorKind(): String =
    this["error"]
        ?.jsonObject
        ?.get("data")
        ?.jsonObject
        ?.get("kind")
        ?.jsonPrimitive
        ?.contentOrNull
        .orEmpty()

internal fun JsonObject.result(): JsonObject = this["result"]?.jsonObject ?: JsonObject(emptyMap())

internal fun JsonObject.resultValue(): JsonElement? = this["result"]
