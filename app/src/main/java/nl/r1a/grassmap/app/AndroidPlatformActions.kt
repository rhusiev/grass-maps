package nl.r1a.grassmap.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.PlatformActions

/**
 * The Android side of the share seam (T-0210).
 *
 * It is the only place a `ClipboardManager` or an `Intent` is named, so the
 * share plugin and the place-card rows hand [PlatformActions] a value and stay
 * platform-free. A text is never logged: a shared message carries coordinates.
 */
class AndroidPlatformActions(
    context: Context,
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

    override fun openUri(uri: String): Outcome<Unit> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        return launch(intent)
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
    }
}
