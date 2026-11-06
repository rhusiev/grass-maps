package nl.r1a.grassmap.control

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** One read from a [FrameTransport]. */
sealed interface FrameRead {
    data class Frame(
        val text: String,
    ) : FrameRead

    data object EndOfStream : FrameRead

    /** A framing violation that ends the connection: an oversized frame. */
    data class Oversized(
        val limitBytes: Int,
    ) : FrameRead
}

/**
 * Newline-delimited JSON, the same framing MCP uses on stdio: one JSON object per
 * line, UTF-8. A transport is the only part of the channel that knows about a
 * socket, so the session is tested without one.
 */
interface FrameTransport {
    suspend fun readFrame(): FrameRead

    suspend fun writeFrame(frame: String): Boolean

    fun close()
}

/**
 * A frame transport over a stream pair. Reads are bounded: a peer that sends
 * [maxFrameBytes] without a newline is disconnected instead of growing a buffer.
 */
class StreamFrameTransport(
    private val input: InputStream,
    private val output: OutputStream,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
) : FrameTransport {
    private val reader = BufferedInputStream(input)

    override suspend fun readFrame(): FrameRead = withContext(ioDispatcher) { readBlocking() }

    override suspend fun writeFrame(frame: String): Boolean =
        withContext(ioDispatcher) {
            try {
                output.write(frame.toByteArray(Charsets.UTF_8))
                output.write(NEWLINE)
                output.flush()
                true
            } catch (exception: IOException) {
                false
            }
        }

    override fun close() {
        closeQuietly(input)
        closeQuietly(output)
    }

    private fun readBlocking(): FrameRead {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val byte =
                try {
                    reader.read()
                } catch (exception: IOException) {
                    return FrameRead.EndOfStream
                }
            if (byte == -1) {
                return FrameRead.EndOfStream
            }
            if (byte == NEWLINE) {
                val line = buffer.toString(Charsets.UTF_8.name()).trimEnd('\r')
                buffer.reset()
                if (line.isBlank()) continue
                return FrameRead.Frame(line)
            }
            buffer.write(byte)
            if (buffer.size() > maxFrameBytes) {
                return FrameRead.Oversized(maxFrameBytes)
            }
        }
    }

    private fun closeQuietly(stream: java.io.Closeable) {
        try {
            stream.close()
        } catch (exception: IOException) {
            // Closing a dead socket is best effort.
        }
    }

    private companion object {
        const val NEWLINE = '\n'.code
        const val DEFAULT_MAX_FRAME_BYTES = 1 shl 20
    }
}
