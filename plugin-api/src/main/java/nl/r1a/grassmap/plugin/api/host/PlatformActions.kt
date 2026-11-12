package nl.r1a.grassmap.plugin.api.host

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * A file the platform can share or save. [bytes] are the finished document;
 * the host chooses a private location for a share and the user's chosen
 * location for a save. No Android type crosses this seam.
 */
data class PlatformFile(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
)

/** What happened when the user was asked where to save [PlatformFile]. */
enum class DocumentSaveResult {
    SAVED,
    CANCELLED,
}

/**
 * The platform effects a share or card action needs (T-0210).
 *
 * The contract carries no Android types and never touches a `Context`, a
 * `ClipboardManager` or an `Intent`, so a plugin can ask for the effect and the
 * host owns the platform call. The design's `PlatformActions` lists
 * `copyToClipboard`, `shareText`, `shareFile` and `openGeoUri`; [openUri]
 * covers the `geo:` case together with the `tel:`, `mailto:` and web targets
 * the place card already models. T-0410 adds [shareFile] and [saveFile].
 *
 * A build without an Android host wires [NoPlatformActions], and every call is
 * a typed [AppError.NotReady] instead of a crash.
 */
interface PlatformActions {
    /** Puts [text] on the system clipboard. */
    fun copyToClipboard(text: String): Outcome<Unit>

    /**
     * Opens the system share sheet with [text]. [subject] becomes the message
     * subject where the receiving app reads one.
     */
    fun shareText(
        text: String,
        subject: String? = null,
    ): Outcome<Unit>

    /**
     * Opens the system share sheet with [file] as one attachment. The host owns
     * the temporary copy and its read grant; the caller's bytes stay private.
     */
    suspend fun shareFile(
        file: PlatformFile,
        subject: String? = null,
    ): Outcome<Unit>

    /**
     * Asks the user for a destination with the system document picker and
     * writes [file] there. The answer is [DocumentSaveResult.CANCELLED] when
     * the user dismisses the picker; no storage permission is involved.
     */
    suspend fun saveFile(file: PlatformFile): Outcome<DocumentSaveResult>

    /**
     * Opens [uri] with an `ACTION_VIEW` intent: a `geo:`, `tel:`, `mailto:` or
     * web address. A URI no installed app handles is [AppError.Unsupported].
     */
    fun openUri(uri: String): Outcome<Unit>
}

/** The inert actions a host without a platform implementation provides. */
object NoPlatformActions : PlatformActions {
    override fun copyToClipboard(text: String): Outcome<Unit> = notReady()

    override fun shareText(
        text: String,
        subject: String?,
    ): Outcome<Unit> = notReady()

    override suspend fun shareFile(
        file: PlatformFile,
        subject: String?,
    ): Outcome<Unit> = notReady()

    override suspend fun saveFile(file: PlatformFile): Outcome<DocumentSaveResult> = notReady()

    override fun openUri(uri: String): Outcome<Unit> = notReady()

    private fun notReady(): Outcome.Failure = Outcome.Failure(AppError.NotReady)
}
