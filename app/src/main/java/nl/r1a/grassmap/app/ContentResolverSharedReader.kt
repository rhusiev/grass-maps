package nl.r1a.grassmap.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.IOException

/** The provider's own description of a shared document. */
private data class SharedDocumentMetadata(
    val displayName: String?,
    val sizeBytes: Long?,
)

/**
 * Reads a document another app shared (T-0416) through the one grant its
 * intent carries, so the app declares no storage permission and never resolves
 * a filesystem path. The name, type and size come from the provider; a provider
 * that refuses the query still allows the stream read, so the name falls back
 * to the URI. The read is bounded by [readSharedDocument].
 */
internal class ContentResolverSharedReader(
    context: Context,
) : SharedDocumentReader {
    private val appContext: Context = context.applicationContext

    override suspend fun read(uri: String): Outcome<SharedDocument> =
        withContext(Dispatchers.IO) {
            val parsed = Uri.parse(uri)
            val metadata = metadata(parsed)
            try {
                val stream =
                    appContext.contentResolver.openInputStream(parsed)
                        ?: return@withContext Outcome.Failure(AppError.NotFound)
                val bytes =
                    stream.use { input ->
                        when (val read = readSharedDocument(input, metadata.sizeBytes)) {
                            is Outcome.Success -> read.value
                            is Outcome.Failure -> return@withContext read
                        }
                    }
                Outcome.Success(
                    SharedDocument(
                        displayName = metadata.displayName,
                        mimeType = appContext.contentResolver.getType(parsed),
                        bytes = bytes,
                    ),
                )
            } catch (exception: SecurityException) {
                Outcome.Failure(AppError.PermissionDenied)
            } catch (exception: IOException) {
                Outcome.Failure(AppError.NotFound)
            } catch (exception: OutOfMemoryError) {
                // The bound above keeps a well-behaved provider inside the heap;
                // a provider that lies about its size could still exhaust it, and
                // refusing the document is better than killing the app.
                Outcome.Failure(AppError.InvalidInput)
            } catch (exception: RuntimeException) {
                Outcome.Failure(AppError.Internal(READ_FAILED))
            }
        }

    /**
     * The display name and size in one provider query. A provider that refuses
     * the query leaves them unset; the stream read then decides.
     */
    private fun metadata(uri: Uri): SharedDocumentMetadata =
        try {
            appContext.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) {
                        return SharedDocumentMetadata(null, null)
                    }
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    SharedDocumentMetadata(
                        displayName = nameIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString),
                        sizeBytes = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong),
                    )
                } ?: SharedDocumentMetadata(null, null)
        } catch (exception: RuntimeException) {
            // A provider that refuses the query may still serve the stream.
            SharedDocumentMetadata(null, null)
        }

    private companion object {
        const val READ_FAILED = "platform.read_shared"
    }
}
