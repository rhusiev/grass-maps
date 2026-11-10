package nl.r1a.grassmap.control

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ControlSessionTest {
    @Test
    fun notificationAuthFailuresCountTowardTheLimit() =
        runTest {
            val harness = Harness(maxFailedAuth = 2)
            val session = backgroundScope.launch { harness.session.run() }

            repeat(2) {
                harness.send(null, ControlSession.METHOD_LIST_COMMANDS)
            }

            withTimeout(1_000) { session.join() }
        }

    @Test
    fun answersMalformedJsonRpcFramesWithoutClosing() =
        runTest {
            val harness = Harness()
            backgroundScope.launch { harness.session.run() }

            val malformed =
                listOf(
                    """{"jsonrpc":{},"method":"x"}""",
                    """{"jsonrpc":5,"method":"x"}""",
                    """{"jsonrpc":"2.0","method":{}}""",
                    """{"jsonrpc":"2.0","method":null}""",
                    """[]""",
                    """not json""",
                )
            for (frame in malformed) {
                harness.transport.send(frame)
                assertEquals(RpcCodes.PARSE_ERROR, harness.nextObject().errorCode())
            }

            harness.authenticate()
            harness.send(2L, ControlSession.METHOD_LIST_COMMANDS)
            assertNull(harness.nextObject()["error"])
        }

    @Test
    fun endsTheSessionWhenThePeerDiesWithALiveSubscription() =
        runTest {
            val values = MutableSharedFlow<JsonElement>()
            val harness = Harness(streams = FakeStreams(mapOf("test.value" to values)))
            val session = backgroundScope.launch { harness.session.run() }
            harness.authenticate()

            harness.send(
                2L,
                ControlSession.METHOD_SUBSCRIBE,
                buildJsonObject { put(ControlSession.STREAM_PARAM, "test.value") },
            )
            val subscription =
                harness
                    .nextObject()
                    .result()["subscription"]
                    ?.let { it as JsonPrimitive }
                    ?.long
            assertTrue(subscription != null)

            harness.transport.close()
            withTimeout(1_000) { session.join() }
        }

    @Test
    fun refusesEveryMethodBeforeAuthentication() =
        runTest {
            val harness = Harness()
            backgroundScope.launch { harness.session.run() }

            harness.send(2L, ControlSession.METHOD_LIST_COMMANDS)
            val response = harness.nextObject()

            assertEquals(RpcCodes.UNAUTHORIZED, response.errorCode())
            assertEquals("unauthorized", response.errorKind())
            assertTrue(harness.invoker.invocations.isEmpty())
        }

    @Test
    fun refusesAWrongTokenWithoutServingAnything() =
        runTest {
            val harness = Harness()
            backgroundScope.launch { harness.session.run() }

            harness.send(2L, ControlSession.METHOD_AUTH, harness.authParams("0".repeat(ControlTokens.TOKEN_HEX_LENGTH)))
            val refusal = harness.nextObject()
            assertEquals(RpcCodes.UNAUTHORIZED, refusal.errorCode())

            harness.send(3L, ControlSession.METHOD_LIST_COMMANDS)
            assertEquals(RpcCodes.UNAUTHORIZED, harness.nextObject().errorCode())
        }

    @Test
    fun closesTheConnectionAfterRepeatedBadTokens() =
        runTest {
            val harness = Harness(maxFailedAuth = 2)
            val session = backgroundScope.launch { harness.session.run() }

            repeat(2) {
                harness.send(AUTH_FAIL_ID, ControlSession.METHOD_AUTH, harness.authParams("bad"))
                assertEquals(RpcCodes.UNAUTHORIZED, harness.nextObject().errorCode())
            }

            withTimeout(1_000) { session.join() }
            assertNull(harness.transport.pending())
        }

    @Test
    fun acceptsTheTokenAndListsCommandsWithTheirPolicy() =
        runTest {
            val harness = Harness()
            backgroundScope.launch { harness.session.run() }
            harness.authenticate()

            harness.send(2L, ControlSession.METHOD_LIST_COMMANDS)
            val response = harness.nextObject()

            val listed = response.result()["commands"] as JsonArray
            assertEquals(2, listed.size)
            val allowed =
                listed.map { it as JsonObject }.single {
                    it["id"]?.jsonPrimitive?.content == "test.allowed"
                }
            assertEquals("Test command.", allowed["doc"]?.jsonPrimitive?.content)
            assertEquals("test", allowed["plugin"]?.jsonPrimitive?.content)
            assertTrue(allowed["allowlisted"]?.jsonPrimitive?.boolean == true)
            assertEquals(JsonNull, allowed["requiresSwitch"])
            assertEquals("default", allowed["dispatcher"]?.jsonPrimitive?.content)
        }

    @Test
    fun refusesACommandThatIsNotAllowlisted() =
        runTest {
            val harness = Harness()
            backgroundScope.launch { harness.session.run() }
            harness.authenticate()

            harness.send(
                2L,
                ControlSession.METHOD_INVOKE_COMMAND,
                buildJsonObject {
                    put(ControlSession.COMMAND_PARAM, "test.hidden")
                    put(ControlSession.PARAMS_PARAM, JsonPrimitive(1))
                },
            )
            val response = harness.nextObject()

            assertEquals(RpcCodes.DENIED, response.errorCode())
            assertEquals("not_allowlisted", response.errorKind())
            assertTrue(harness.invoker.invocations.isEmpty())
        }

    @Test
    fun refusesAnUnknownCommand() =
        runTest {
            val harness = Harness()
            backgroundScope.launch { harness.session.run() }
            harness.authenticate()

            harness.send(
                2L,
                ControlSession.METHOD_INVOKE_COMMAND,
                buildJsonObject { put(ControlSession.COMMAND_PARAM, "test.missing") },
            )

            assertEquals(RpcCodes.NOT_FOUND, harness.nextObject().errorCode())
        }

    @Test
    fun invokesAnAllowlistedCommandThroughTheBus() =
        runTest {
            val invoker = RecordingInvoker(Outcome.Success(JsonPrimitive(11)))
            val harness = Harness(invoker = invoker)
            backgroundScope.launch { harness.session.run() }
            harness.authenticate()

            harness.send(
                2L,
                ControlSession.METHOD_INVOKE_COMMAND,
                buildJsonObject {
                    put(ControlSession.COMMAND_PARAM, "test.allowed")
                    put(ControlSession.PARAMS_PARAM, JsonPrimitive(11))
                },
            )
            val response = harness.nextObject()

            assertEquals(JsonPrimitive(11), response.resultValue())
            assertEquals(1, invoker.invocations.size)
            assertEquals(
                "test.allowed",
                invoker.invocations
                    .single()
                    .first.value,
            )
        }

    @Test
    fun returnsATypedSwitchRefusalFromTheBus() =
        runTest {
            val invoker = RecordingInvoker(Outcome.Failure(AppError.SwitchOff(SwitchId("network.test"))))
            val harness = Harness(invoker = invoker)
            backgroundScope.launch { harness.session.run() }
            harness.authenticate()

            harness.send(
                2L,
                ControlSession.METHOD_INVOKE_COMMAND,
                buildJsonObject { put(ControlSession.COMMAND_PARAM, "test.allowed") },
            )
            val response = harness.nextObject()

            assertEquals(RpcCodes.SWITCH_OFF, response.errorCode())
            assertEquals(
                "network.test",
                response["error"]
                    ?.let { it as JsonObject }
                    ?.get("data")
                    ?.let { it as JsonObject }
                    ?.get("switch")
                    ?.jsonPrimitive
                    ?.content,
            )
        }

    @Test
    fun streamsValuesToTheSubscriberUntilUnsubscribed() =
        runTest {
            val values = MutableSharedFlow<JsonElement>(replay = 1)
            val harness = Harness(streams = FakeStreams(mapOf("test.value" to values)))
            backgroundScope.launch { harness.session.run() }
            harness.authenticate()

            harness.send(
                2L,
                ControlSession.METHOD_SUBSCRIBE,
                buildJsonObject { put(ControlSession.STREAM_PARAM, "test.value") },
            )
            val subscription =
                harness
                    .nextObject()
                    .result()["subscription"]
                    ?.jsonPrimitive
                    ?.long
            assertEquals(1L, subscription)

            values.emit(JsonPrimitive(7))
            val notification = harness.nextObject()
            assertEquals(JsonRpcCodec.NOTIFICATION_STREAM_DATA, notification["method"]?.jsonPrimitive?.content)
            val params = notification["params"] as JsonObject
            assertEquals(1L, params["subscription"]?.jsonPrimitive?.long)
            assertEquals("test.value", params["stream"]?.jsonPrimitive?.content)
            assertEquals(JsonPrimitive(7), params["value"])

            harness.send(
                3L,
                ControlSession.METHOD_UNSUBSCRIBE,
                buildJsonObject { put(ControlSession.SUBSCRIPTION_PARAM, 1) },
            )
            assertEquals(JsonObject(emptyMap()), harness.nextObject().resultValue())

            values.emit(JsonPrimitive(8))
            harness.send(4L, ControlSession.METHOD_LIST_COMMANDS)
            val next = harness.nextObject()
            assertEquals("4", next["id"]?.toString())
        }

    @Test
    fun refusesAnUnknownStreamAndAnUnknownSubscription() =
        runTest {
            val harness = Harness()
            backgroundScope.launch { harness.session.run() }
            harness.authenticate()

            harness.send(
                2L,
                ControlSession.METHOD_SUBSCRIBE,
                buildJsonObject { put(ControlSession.STREAM_PARAM, "test.missing") },
            )
            assertEquals("unknown_stream", harness.nextObject().errorKind())

            harness.send(
                3L,
                ControlSession.METHOD_UNSUBSCRIBE,
                buildJsonObject { put(ControlSession.SUBSCRIPTION_PARAM, 99) },
            )
            assertEquals("not_subscribed", harness.nextObject().errorKind())
        }

    @Test
    fun returnsAScreenshotAsBase64Png() =
        runTest {
            val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
            val harness =
                Harness(
                    screenshot = { Outcome.Success(ControlCapture(png = png, source = "pixel_copy")) },
                )
            backgroundScope.launch { harness.session.run() }
            harness.authenticate()

            harness.send(2L, ControlSession.METHOD_SCREENSHOT)
            val result = harness.nextObject().result()

            assertEquals("png", result["format"]?.jsonPrimitive?.content)
            assertEquals("pixel_copy", result["source"]?.jsonPrimitive?.content)
            assertEquals(
                Base64.getEncoder().encodeToString(png),
                result["base64"]?.jsonPrimitive?.content,
            )
            assertEquals(
                png.toList(),
                Base64.getDecoder().decode(result["base64"]?.jsonPrimitive?.content).toList(),
            )
        }

    @Test
    fun refusesScreenshotWhenNoCaptureIsWired() =
        runTest {
            val harness = Harness()
            backgroundScope.launch { harness.session.run() }
            harness.authenticate()

            harness.send(2L, ControlSession.METHOD_SCREENSHOT)

            assertEquals(RpcCodes.METHOD_NOT_FOUND, harness.nextObject().errorCode())
        }

    @Test
    fun refusesAnUnknownMethodAndAMalformedFrame() =
        runTest {
            val harness = Harness()
            backgroundScope.launch { harness.session.run() }
            harness.authenticate()

            harness.send(2L, "control.unknown")
            assertEquals(RpcCodes.METHOD_NOT_FOUND, harness.nextObject().errorCode())

            harness.transport.send("{not json")
            val parseError = harness.nextObject()
            assertEquals(RpcCodes.PARSE_ERROR, parseError.errorCode())
            assertEquals(JsonNull, parseError["id"])
        }

    @Test
    fun doesNotAnswerANotification() =
        runTest {
            val harness = Harness()
            backgroundScope.launch { harness.session.run() }
            harness.authenticate()

            harness.send(null, ControlSession.METHOD_LIST_COMMANDS)
            harness.send(9L, ControlSession.METHOD_LIST_COMMANDS)

            assertEquals("9", harness.nextObject()["id"]?.toString())
        }

    @Test
    fun ignoresAnInvocationWithoutACommandField() =
        runTest {
            val harness = Harness()
            backgroundScope.launch { harness.session.run() }
            harness.authenticate()

            harness.send(2L, ControlSession.METHOD_INVOKE_COMMAND, JsonObject(emptyMap()))

            assertEquals(RpcCodes.INVALID_PARAMS, harness.nextObject().errorCode())
        }

    @Test
    fun packRefusalsAreTypedAndNeverInternal() =
        runTest {
            val cases =
                mapOf(
                    AppError.RoutingPackUnavailable.Reason.Absent to
                        ("routing_pack_absent" to "routing pack absent"),
                    AppError.RoutingPackUnavailable.Reason.Incomplete to
                        ("routing_pack_incomplete" to "routing pack incomplete"),
                    AppError.RoutingPackUnavailable.Reason.Unusable to
                        ("routing_pack_unusable" to "routing pack unusable"),
                )
            for ((reason, expected) in cases) {
                val (kind, message) = expected
                val invoker = RecordingInvoker(Outcome.Failure(AppError.RoutingPackUnavailable(reason, "detail")))
                val harness = Harness(invoker = invoker)
                backgroundScope.launch { harness.session.run() }
                harness.authenticate()

                harness.send(
                    2L,
                    ControlSession.METHOD_INVOKE_COMMAND,
                    buildJsonObject { put(ControlSession.COMMAND_PARAM, "test.allowed") },
                )
                val response = harness.nextObject()

                assertEquals(kind, response.errorKind())
                assertEquals(
                    message,
                    response["error"]
                        ?.let { it as JsonObject }
                        ?.get("message")
                        ?.jsonPrimitive
                        ?.content,
                )
                assertNotEquals(RpcCodes.INTERNAL, response.errorCode())
                assertEquals(RpcCodes.ROUTING_PACK, response.errorCode())
                assertTrue(
                    harness.log.warnings
                        .single()
                        .contains("test.allowed"),
                )
                harness.transport.close()
            }
        }

    @Test
    fun anEscapingCommandExceptionIsLoggedWithItsCommandAndStackTrace() =
        runTest {
            val failure = IllegalStateException("native actor failed")
            val harness = Harness(invoker = RecordingInvoker(thrown = failure))
            backgroundScope.launch { harness.session.run() }
            harness.authenticate()

            harness.send(
                2L,
                ControlSession.METHOD_INVOKE_COMMAND,
                buildJsonObject { put(ControlSession.COMMAND_PARAM, "test.allowed") },
            )
            val response = harness.nextObject()

            assertEquals(RpcCodes.INTERNAL, response.errorCode())
            assertEquals("handler_failure", response.errorKind())
            val (message, throwable) = harness.log.errors.single()
            assertTrue(message.contains("test.allowed"))
            assertSame(failure, throwable)
        }

    private companion object {
        const val AUTH_FAIL_ID = 7L
    }
}
