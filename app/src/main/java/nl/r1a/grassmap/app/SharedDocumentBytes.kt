package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * The largest shared document this app will read (T-0416).
 *
 * A one-point-per-second track with latitude, longitude, elevation and time is
 * about 120 bytes of GPX, so 16 MiB is more than 130 000 points: about 36 hours
 * at 1 Hz, or a multi-day multi-track export. A whole-library export is larger
 * than that, and it belongs to the library import rather than to a share. The
 * bound also keeps the parse safe: `file.gpx` builds a DOM at several times the
 * document size, so a bigger share would push the app's heap rather than the
 * importer's own limits.
 */
internal const val MAX_SHARED_BYTES: Long = 16L * 1024L * 1024L

private const val READ_BUFFER_BYTES = 8 * 1024
private const val DEFAULT_CAPACITY_BYTES = 64 * 1024

/**
 * Reads a shared document into memory under [MAX_SHARED_BYTES].
 *
 * [declaredSize] is the provider's `OpenableColumns.SIZE`. It is only a hint:
 * a provider may omit it or understate it, so the stream is read to its end and
 * the bound stops a document that is larger than declared. A document over the
 * bound is [AppError.InvalidInput] before its last byte is kept, so the read
 * never grows past the bound.
 */
internal fun readSharedDocument(
    input: InputStream,
    declaredSize: Long?,
): Outcome<ByteArray> {
    if (declaredSize != null && declaredSize > MAX_SHARED_BYTES) {
        return Outcome.Failure(AppError.InvalidInput)
    }
    val expected = (declaredSize ?: DEFAULT_CAPACITY_BYTES.toLong()).coerceAtMost(MAX_SHARED_BYTES)
    val output = ByteArrayOutputStream(expected.toInt())
    val buffer = ByteArray(READ_BUFFER_BYTES)
    try {
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            if (output.size().toLong() + read > MAX_SHARED_BYTES) {
                return Outcome.Failure(AppError.InvalidInput)
            }
            output.write(buffer, 0, read)
        }
    } catch (exception: IOException) {
        return Outcome.Failure(AppError.NotFound)
    }
    val bytes = output.toByteArray()
    return if (bytes.isEmpty()) Outcome.Failure(AppError.InvalidInput) else Outcome.Success(bytes)
}
