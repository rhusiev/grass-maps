package nl.r1a.grassmap.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.error.map
import nl.r1a.grassmap.plugin.api.host.DocumentSaveResult
import nl.r1a.grassmap.plugin.api.host.PlatformFile

/** Writes [file] to the document URI the user chose. */
fun interface DocumentWriter {
    suspend fun write(
        uri: String,
        file: PlatformFile,
    ): Outcome<Unit>
}

/**
 * The one path from an export to the system document picker (T-0410).
 *
 * The activity binds a launcher and delivers the chosen URI here, so an
 * activity recreation keeps a pending request instead of dropping the bytes.
 * The coordinator owns no Android type: the picker intent and the content
 * resolver live in `MainActivity` and [ContentResolverDocumentWriter], and the
 * tests drive the same seam with a recording launcher and writer.
 *
 * Only one save may be pending. A second request is [AppError.NotReady] rather
 * than a second picker over the first, and a request with no bound activity is
 * the same value. When the user dismisses the picker the answer is
 * [DocumentSaveResult.CANCELLED]; an unanswered request times out as
 * [AppError.Cancelled].
 */
class DocumentSaveCoordinator(
    private val writer: DocumentWriter = DocumentWriter { _, _ -> Outcome.Failure(AppError.NotReady) },
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    private val gate = Mutex()

    @Volatile
    private var owner: Any? = null

    @Volatile
    private var launcher: ((PlatformFile) -> Unit)? = null

    @Volatile
    private var pending: CompletableDeferred<String?>? = null

    /** Binds the activity that can show the picker. */
    fun bind(
        owner: Any,
        launcher: (PlatformFile) -> Unit,
    ) {
        this.owner = owner
        this.launcher = launcher
    }

    /** Clears the binding only when [owner] is still the bound activity. */
    fun unbind(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            launcher = null
        }
    }

    suspend fun save(file: PlatformFile): Outcome<DocumentSaveResult> {
        if (!gate.tryLock()) {
            return Outcome.Failure(AppError.NotReady)
        }
        try {
            val launch = launcher ?: return Outcome.Failure(AppError.NotReady)
            val answer = CompletableDeferred<String?>()
            pending = answer
            try {
                launch(file)
            } catch (exception: RuntimeException) {
                pending = null
                return Outcome.Failure(AppError.Unsupported)
            }
            val uri = withTimeoutOrNull(timeoutMillis) { answer.await() }
            if (pending === answer) {
                pending = null
            }
            return when {
                uri == null && answer.isCompleted -> Outcome.Success(DocumentSaveResult.CANCELLED)
                uri == null -> Outcome.Failure(AppError.Cancelled)
                else -> writer.write(uri, file).map { DocumentSaveResult.SAVED }
            }
        } finally {
            gate.unlock()
        }
    }

    /** Delivers the picker's answer; a null URI means the user dismissed it. */
    fun complete(uri: String?) {
        pending?.complete(uri)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Long = 5 * 60_000L
    }
}
