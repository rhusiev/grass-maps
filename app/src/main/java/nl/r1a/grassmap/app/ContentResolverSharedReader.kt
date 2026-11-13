package nl.r1a.grassmap.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Reads a document another app shared (T-0416) through the one grant its
 * intent carries, so the app declares no storage permission and never resolves
 * a filesystem path. The name and type come from the provider; a provider that
 * refuses the query still allows the stream read, so the name falls back to the
 * URI. The read is bounded: an import larger than [MAX_SHARED_BYTES] is
 * refused instead of being held in memory.
 */
internal class ContentResolverSharedReader(
    context: Context,
) : SharedDocumentReader {
    private val appContext: Context = context.applicationContext

    override suspend fun read(uri: String): Outcome<SharedDocument> =
        withContext(Dispatchers.IO) {
            val parsed = Uri.parse(uri)
            try {
                val bytes =
                    appContext.contentResolver.openInputStream(parsed)?.use { input -> input.readBounded() }
                        ?: return@withContext Outcome.Failure(AppError.NotFound)
                if (bytes.isEmpty()) {
                    return@withContext Outcome.Failure(AppError.InvalidInput)
                }
                Outcome.Success(
                    SharedDocument(
                        displayName = displayName(parsed),
                        mimeType = appContext.contentResolver.getType(parsed),
                        bytes = bytes,
                    ),
                )
            } catch (exception: SecurityException) {
                Outcome.Failure(AppError.PermissionDenied)
            } catch (exception: IOException) {
                Outcome.Failure(AppError.NotFound)
            } catch (exception: RuntimeException) {
                Outcome.Failure(AppError.Internal(READ_FAILED))
            }
        }

    private fun displayName(uri: Uri): String? =
        try {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (exception: RuntimeException) {
            // A provider that refuses the name query may still serve the stream.
            null
        }

    /** The stream's bytes, or null when it exceeds the import bound. */
    private fun InputStream.readBounded(): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (true) {
            val read = read(buffer)
            if (read < 0) {
                return output.toByteArray()
            }
            output.write(buffer, 0, read)
            if (output.size() > MAX_SHARED_BYTES) {
                return null
            }
        }
    }

    private companion object {
        const val MAX_SHARED_BYTES = 64 * 1024 * 1024
        const val READ_BUFFER_BYTES = 8 * 1024
        const val READ_FAILED = "platform.read_shared"
    }
}
