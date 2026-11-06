package nl.r1a.grassmap.control

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.SequenceInputStream

class ControlFramingTest {
    @Test
    fun readsOneFramePerLine() {
        val transport = StreamFrameTransport(ByteArrayInputStream("first\nsecond\n".toByteArray()), ByteArrayOutputStream())

        assertEquals(FrameRead.Frame("first"), transport.readFrameBlocking())
        assertEquals(FrameRead.Frame("second"), transport.readFrameBlocking())
        assertEquals(FrameRead.EndOfStream, transport.readFrameBlocking())
    }

    @Test
    fun joinsAFrameSplitAcrossReads() {
        val input =
            SequenceInputStream(
                ByteArrayInputStream("{\"jsonrpc\":\"2.0\",".toByteArray()),
                ByteArrayInputStream("\"id\":7}\n".toByteArray()),
            )
        val transport = StreamFrameTransport(input, ByteArrayOutputStream())

        assertEquals(FrameRead.Frame("{\"jsonrpc\":\"2.0\",\"id\":7}"), transport.readFrameBlocking())
    }

    @Test
    fun ignoresBlankLinesAndStripsCarriageReturn() {
        val transport = StreamFrameTransport(ByteArrayInputStream("a\r\n\n  \nb\n".toByteArray()), ByteArrayOutputStream())

        assertEquals(FrameRead.Frame("a"), transport.readFrameBlocking())
        assertEquals(FrameRead.Frame("b"), transport.readFrameBlocking())
    }

    @Test
    fun refusesAFrameOverTheLimit() {
        val transport =
            StreamFrameTransport(
                input = ByteArrayInputStream(ByteArray(64) { 'x'.code.toByte() }),
                output = ByteArrayOutputStream(),
                maxFrameBytes = 16,
            )

        assertEquals(FrameRead.Oversized(16), transport.readFrameBlocking())
    }

    @Test
    fun writesOneFramePerLine() {
        val output = ByteArrayOutputStream()
        val transport = StreamFrameTransport(ByteArrayInputStream(ByteArray(0)), output)

        assertTrue(transport.writeFrameBlocking("{\"ok\":true}"))

        assertEquals("{\"ok\":true}\n", output.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun parsesARequestAndKeepsTheIdType() {
        val parsed =
            when (val outcome = JsonRpcCodec.parse(requestFrame(19L, "control.commands.list"))) {
                is Outcome.Success -> outcome.value
                is Outcome.Failure -> null
            }

        assertEquals("control.commands.list", parsed?.method)
        assertEquals("19", parsed?.id?.toString())
    }

    @Test
    fun acceptsANotificationWithoutAnId() {
        val parsed =
            when (val outcome = JsonRpcCodec.parse(requestFrame(null, "control.commands.list"))) {
                is Outcome.Success -> outcome.value
                is Outcome.Failure -> null
            }

        assertNull(parsed?.id)
    }

    @Test
    fun rejectsMalformedEnvelopes() {
        listOf(
            "{",
            "[]",
            "{\"jsonrpc\":\"1.0\",\"method\":\"x\"}",
            "{\"jsonrpc\":\"2.0\"}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"\"}",
        ).forEach { text ->
            assertTrue("'$text' must be refused", JsonRpcCodec.parse(text) is Outcome.Failure)
        }
    }

    @Test
    fun encodesAResponseWithItsId() {
        val encoded = JsonRpcCodec.response(JsonPrimitive(5), JsonPrimitive(11))

        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":5,\"result\":11}", encoded)
        assertFalse(encoded.contains("error"))
    }

    @Test
    fun encodesATypedRefusal() {
        val encoded =
            JsonRpcCodec.error(
                id = null,
                error = JsonRpcCodec.refusal(RpcCodes.DENIED, "not_allowlisted", "command is not allowlisted"),
            )

        assertTrue(encoded.contains("\"code\":-32004"))
        assertTrue(encoded.contains("\"kind\":\"not_allowlisted\""))
        assertTrue(encoded.contains("\"id\":null"))
    }

    private fun StreamFrameTransport.readFrameBlocking(): FrameRead = runBlocking { readFrame() }

    private fun StreamFrameTransport.writeFrameBlocking(frame: String): Boolean = runBlocking { writeFrame(frame) }
}
