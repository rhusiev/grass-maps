package nl.r1a.grassmap.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.PlatformFile
import java.io.IOException

/**
 * Writes the chosen document through the URI the Storage Access Framework
 * returned. The grant belongs to that one URI, so the app declares no storage
 * permission and never resolves a filesystem path.
 */
internal class ContentResolverDocumentWriter(
    context: Context,
) : DocumentWriter {
    private val appContext: Context = context.applicationContext

    override suspend fun write(
        uri: String,
        file: PlatformFile,
    ): Outcome<Unit> =
        withContext(Dispatchers.IO) {
            val parsed = Uri.parse(uri)
            if (parsed.scheme != CONTENT_SCHEME) {
                return@withContext Outcome.Failure(AppError.InvalidInput)
            }
            try {
                val stream =
                    appContext.contentResolver.openOutputStream(parsed, WRITE_MODE)
                        ?: return@withContext Outcome.Failure(AppError.NotFound)
                stream.use { output -> output.write(file.bytes) }
                Outcome.Success(Unit)
            } catch (exception: SecurityException) {
                Outcome.Failure(AppError.PermissionDenied)
            } catch (exception: IOException) {
                Outcome.Failure(AppError.Internal(SAVE_FAILED))
            } catch (exception: RuntimeException) {
                Outcome.Failure(AppError.Internal(SAVE_FAILED))
            }
        }

    private companion object {
        const val CONTENT_SCHEME = "content"
        const val WRITE_MODE = "wt"
        const val SAVE_FAILED = "platform.save_file"
    }
}

/** The `ACTION_CREATE_DOCUMENT` intent the system picker receives (T-0410). */
internal object AndroidDocumentIntents {
    fun createDocument(file: PlatformFile): Intent {
        val request = DocumentCreateRequest.of(file)
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = request.mimeType
            putExtra(Intent.EXTRA_TITLE, request.suggestedName)
        }
    }
}

/**
 * The values the picker intent carries (T-0410). The intent itself is an
 * Android type the JVM tests cannot build, so the suggested name and the filter
 * are a value those tests can name.
 */
internal data class DocumentCreateRequest(
    val mimeType: String,
    val suggestedName: String,
) {
    companion object {
        fun of(file: PlatformFile): DocumentCreateRequest = DocumentCreateRequest(mimeType = file.mimeType, suggestedName = file.fileName)
    }
}
