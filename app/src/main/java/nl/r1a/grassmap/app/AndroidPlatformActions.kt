package nl.r1a.grassmap.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.DocumentSaveResult
import nl.r1a.grassmap.plugin.api.host.PlatformActions
import nl.r1a.grassmap.plugin.api.host.PlatformFile
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * The Android side of the share seam (T-0210, T-0410).
 *
 * It is the only place a `ClipboardManager`, a `FileProvider` or a chooser
 * `Intent` is named, so the share plugin, the track export and the place-card
 * rows hand [PlatformActions] a value and stay platform-free. A text is never
 * logged: a shared message carries coordinates. A shared file is written under
 * the app's cache, where the manifest's FileProvider exposes exactly that one
 * directory for the read grant.
 */
class AndroidPlatformActions(
    context: Context,
    private val documentSaver: DocumentSaveCoordinator = DocumentSaveCoordinator(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PlatformActions {
    private val appContext: Context = context.applicationContext

    override fun copyToClipboard(text: String): Outcome<Unit> =
        try {
            val clipboard =
                appContext.getSystemService(ClipboardManager::class.java)
                    ?: return Outcome.Failure(AppError.NotReady)
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
            Outcome.Success(Unit)
        } catch (exception: SecurityException) {
            Outcome.Failure(AppError.PermissionDenied)
        } catch (exception: RuntimeException) {
            Outcome.Failure(AppError.Internal(COPY_FAILED))
        }

    override fun shareText(
        text: String,
        subject: String?,
    ): Outcome<Unit> {
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = MIME_TEXT
                putExtra(Intent.EXTRA_TEXT, text)
                subject
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { title -> putExtra(Intent.EXTRA_SUBJECT, title) }
            }
        return launch(Intent.createChooser(send, null))
    }

    override suspend fun shareFile(
        file: PlatformFile,
        subject: String?,
    ): Outcome<Unit> {
        val cached =
            withContext(ioDispatcher) {
                writeShareCopy(file)
            }
        val target =
            when (cached) {
                is Outcome.Success -> cached.value
                is Outcome.Failure -> return cached
            }
        val uri =
            try {
                FileProvider.getUriForFile(appContext, trackFileProviderAuthority(appContext.packageName), target)
            } catch (exception: IllegalArgumentException) {
                return Outcome.Failure(AppError.Internal(SHARE_FAILED))
            }
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = file.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                subject
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { title -> putExtra(Intent.EXTRA_SUBJECT, title) }
                clipData = ClipData.newRawUri(subject ?: file.fileName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        return launch(Intent.createChooser(send, null))
    }

    override suspend fun saveFile(file: PlatformFile): Outcome<DocumentSaveResult> = documentSaver.save(file)

    override fun openUri(uri: String): Outcome<Unit> {
        val parsed = Uri.parse(uri)
        if (!isAllowedScheme(parsed.scheme)) {
            return Outcome.Failure(AppError.Unsupported)
        }
        return launch(Intent(Intent.ACTION_VIEW, parsed))
    }

    private fun writeShareCopy(file: PlatformFile): Outcome<File> =
        try {
            val directory = File(appContext.cacheDir, SHARE_DIRECTORY)
            if (!directory.isDirectory && !directory.mkdirs()) {
                return Outcome.Failure(AppError.Internal(SHARE_FAILED))
            }
            val target = File(directory, safeName(file.fileName))
            val partial = File(directory, ".${target.name}.part")
            partial.writeBytes(file.bytes)
            if (!partial.renameTo(target)) {
                partial.delete()
                return Outcome.Failure(AppError.Internal(SHARE_FAILED))
            }
            Outcome.Success(target)
        } catch (exception: IOException) {
            Outcome.Failure(AppError.Internal(SHARE_FAILED))
        } catch (exception: SecurityException) {
            Outcome.Failure(AppError.PermissionDenied)
        }

    /** A share name is data from a track; never let it name a parent directory. */
    private fun safeName(fileName: String): String {
        val repaired =
            buildString {
                for (character in fileName) {
                    when {
                        character.code < FIRST_PRINTABLE -> append('-')
                        character == '/' || character == '\\' -> append('-')
                        else -> append(character)
                    }
                }
            }.trim()
                .trim('.')
        return repaired.ifEmpty { DEFAULT_SHARE_NAME }
    }

    private fun launch(intent: Intent): Outcome<Unit> =
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            Outcome.Success(Unit)
        } catch (exception: ActivityNotFoundException) {
            Outcome.Failure(AppError.Unsupported)
        } catch (exception: SecurityException) {
            Outcome.Failure(AppError.PermissionDenied)
        }

    private companion object {
        const val CLIP_LABEL = "Grass Maps coordinates"
        const val MIME_TEXT = "text/plain"
        const val COPY_FAILED = "platform.copy"
        const val SHARE_FAILED = "platform.share_file"
        const val SHARE_DIRECTORY = "shared-tracks"
        const val DEFAULT_SHARE_NAME = "track.gpx"
        const val FIRST_PRINTABLE = 0x20
    }
}

/**
 * The authority of the app's track FileProvider, `${applicationId}.track.files`
 * (T-0410). The manifest declares it and the share import refuses a `content:`
 * URI under it, so both sides name it the same way.
 */
internal fun trackFileProviderAuthority(packageName: String): String = packageName + TRACK_FILE_PROVIDER_SUFFIX

private const val TRACK_FILE_PROVIDER_SUFFIX = ".track.files"

/**
 * The schemes an `ACTION_VIEW` intent may carry (T-0210).
 *
 * This is the second line of defence behind [webUri]: a caller that reaches the
 * seam with an `intent:`, `file:`, `content:` or `javascript:` URI is refused
 * before an intent is built. `http`, `https`, `tel`, `mailto` and `geo` are the
 * only schemes the card and the share commands produce.
 */
internal fun isAllowedScheme(scheme: String?): Boolean = scheme?.lowercase(Locale.ROOT) in ALLOWED_OPEN_SCHEMES

private val ALLOWED_OPEN_SCHEMES = setOf("http", "https", "tel", "mailto", "geo")
