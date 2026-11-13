package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.ui.shell.TrackImportFailure
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/** The shared document's size bound (T-0416). */
class SharedDocumentBytesTest {
    @Test
    fun aDocumentUnderTheBoundIsReadWhole() {
        val bytes = "gpx bytes".toByteArray()

        val read = readSharedDocument(ByteArrayInputStream(bytes), declaredSize = bytes.size.toLong())

        assertArrayEquals(bytes, success(read))
    }

    @Test
    fun aProviderThatDeclaresAnOversizeDocumentIsRefusedBeforeItIsRead() {
        val read = readSharedDocument(UnreadableStream(), declaredSize = MAX_SHARED_BYTES + 1)

        assertEquals(Outcome.Failure(AppError.InvalidInput), read)
    }

    @Test
    fun aStreamPastTheBoundIsRefusedEvenWhenTheDeclaredSizeIsSmall() {
        val read = readSharedDocument(SizedStream(MAX_SHARED_BYTES + 1), declaredSize = 1)

        assertEquals(Outcome.Failure(AppError.InvalidInput), read)
    }

    @Test
    fun aDocumentOfExactlyTheBoundIsAccepted() {
        val read = readSharedDocument(SizedStream(MAX_SHARED_BYTES), declaredSize = null)

        assertEquals(MAX_SHARED_BYTES, success(read).size.toLong())
    }

    @Test
    fun aStreamLongerThanDeclaredButUnderTheBoundKeepsEveryByte() {
        val bytes = ByteArray(100) { index -> index.toByte() }

        val read = readSharedDocument(ByteArrayInputStream(bytes), declaredSize = 10)

        assertArrayEquals(bytes, success(read))
    }

    @Test
    fun anEmptyDocumentIsRefused() {
        assertEquals(
            Outcome.Failure(AppError.InvalidInput),
            readSharedDocument(ByteArrayInputStream(ByteArray(0)), declaredSize = 0),
        )
    }

    @Test
    fun aStreamThatFailsWhileReadingIsNotFound() {
        val read = readSharedDocument(FailingStream(), declaredSize = null)

        assertEquals(Outcome.Failure(AppError.NotFound), read)
    }

    @Test
    fun anOversizedShareIsReportedAsNotATrack() {
        assertEquals(TrackImportFailure.NotATrack, trackImportFailure(AppError.InvalidInput))
    }

    private fun success(outcome: Outcome<ByteArray>): ByteArray =
        when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("read failed: ${outcome.error}")
        }

    /** A stream that yields [size] zero bytes without allocating them. */
    private class SizedStream(
        private val size: Long,
    ) : java.io.InputStream() {
        private var position = 0L

        override fun read(): Int = if (position++ < size) 0 else -1

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (position >= size) {
                return -1
            }
            val count = minOf(length.toLong(), size - position).toInt()
            buffer.fill(0, offset, offset + count)
            position += count
            return count
        }
    }

    private class UnreadableStream : java.io.InputStream() {
        override fun read(): Int = throw AssertionError("the reader must not read a document it already refused")

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = throw AssertionError("the reader must not read a document it already refused")
    }

    private class FailingStream : java.io.InputStream() {
        override fun read(): Int = throw IOException("the provider broke")

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = throw IOException("the provider broke")
    }
}
