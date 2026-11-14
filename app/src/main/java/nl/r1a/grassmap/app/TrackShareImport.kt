package nl.r1a.grassmap.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.tracks.StoredTrack
import nl.r1a.grassmap.ui.shell.TrackImportFailure
import nl.r1a.grassmap.ui.shell.TrackImportOutcome
import java.util.concurrent.atomic.AtomicLong

/** The share-sheet import's log tag. */
internal const val TRACK_SHARE_TAG = "GrassMapsTracks"

/**
 * The parts of a share intent the import reads (T-0416), with no Android type,
 * so the rule is testable on the JVM.
 *
 * The system already chose this app for the intent by its `ACTION_SEND` filter,
 * so the rule only decides whether the payload is a document this app may read:
 * a `content:` URI from another provider, from the stream extra or from the data
 * URI a sender uses instead of the extra. A `file:` URI names a filesystem path
 * and is refused, and so is a `content:` URI under this app's own track
 * FileProvider: a share from outside must not become a way to read the app's
 * private storage. A `geo:` or `https:` link is never a document, which is why
 * [ACTION_VIEW] can share this rule with `ACTION_SEND` without touching the
 * link path.
 */
internal object TrackShareIntent {
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_VIEW = "android.intent.action.VIEW"

    private const val CONTENT_SCHEME = "content"
    private const val AUTHORITY_MARKER = "://"
    private const val GPX_MIME = "application/gpx+xml"
    private const val GPX_EXTENSION = "gpx"
    private const val UNNAMED_DOCUMENT = "shared"

    /**
     * The document URI a share carries, or null when the intent is not a
     * document share or names a document this app must not read.
     */
    fun documentUri(
        action: String?,
        streamUri: String?,
        dataUri: String?,
        ownProviderAuthority: String,
    ): String? {
        if (action != ACTION_SEND && action != ACTION_VIEW) {
            return null
        }
        val uri = streamUri?.takeIf { it.isNotBlank() } ?: dataUri?.takeIf { it.isNotBlank() } ?: return null
        val scheme = uri.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme != CONTENT_SCHEME) {
            return null
        }
        return uri.takeUnless { candidate -> authorityOf(candidate).equals(ownProviderAuthority, ignoreCase = true) }
    }

    /** The authority between `://` and the next `/`, `?` or `#`, or empty when absent. */
    private fun authorityOf(uri: String): String =
        uri
            .substringAfter(AUTHORITY_MARKER, missingDelimiterValue = "")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')

    /**
     * The name the importer needs to choose a format. A sender's display name
     * wins; without one the URI's last segment is the name. Android does not
     * know the GPX extension in its MIME map, and a sender that does not name
     * the document would otherwise have no extension at all, so a document the
     * sender typed as GPX gets the extension the format resolves.
     */
    fun fileName(
        displayName: String?,
        uri: String,
        mimeType: String?,
    ): String {
        val named =
            displayName
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: uri.substringAfterLast('/').substringBefore('?').takeIf { it.isNotEmpty() }
                ?: return if (mimeType.isGpxMime()) "$UNNAMED_DOCUMENT.$GPX_EXTENSION" else UNNAMED_DOCUMENT
        return if (named.hasGpxExtension() || !mimeType.isGpxMime()) named else "$named.$GPX_EXTENSION"
    }

    private fun String.hasGpxExtension(): Boolean =
        substringAfterLast('.', missingDelimiterValue = "").equals(GPX_EXTENSION, ignoreCase = true)

    private fun String?.isGpxMime(): Boolean = this?.substringBefore(';')?.trim()?.equals(GPX_MIME, ignoreCase = true) == true
}

/** One shared document as its sender handed it over. */
internal data class SharedDocument(
    val displayName: String?,
    val mimeType: String?,
    val bytes: ByteArray,
)

/**
 * A share that parsed and waits for the user's confirmation (T-0416).
 *
 * [fileName] is the name the sender's provider gave, and [trackCount] is what
 * the parse found. The document stays private to the importer: the sheet draws
 * the first two fields and only [TrackShareImport.confirm] can read the bytes.
 */
internal class PendingTrackShare(
    val fileName: String,
    val trackCount: Int,
    private val shared: SharedDocument,
) {
    /** The document a confirmation would import. */
    fun document(): SharedDocument = shared
}

/** Reads a shared URI. The activity supplies the content resolver; a test supplies bytes. */
internal fun interface SharedDocumentReader {
    suspend fun read(uri: String): Outcome<SharedDocument>
}

/** One notice with the sequence that tells a repeat of the same text apart. */
internal data class TrackShareState(
    val outcome: TrackImportOutcome,
    val sequence: Long,
)

/**
 * The one path from another app's share sheet to the track library (T-0416).
 *
 * It reads the URI through a [SharedDocumentReader] and parses the bytes
 * through the same [TrackAccess] the library uses, then publishes
 * [confirmation] and stops. Nothing reaches the library until [confirm]; a
 * document that does not parse publishes the existing failure notice instead
 * and never asks, because there is nothing to confirm. [cancel] drops the
 * document with no write and no notice.
 *
 * [confirm] imports through the same [TrackAccess] the library uses, and
 * publishes the notice the map draws. The document hash is the track id, so
 * sharing the same file twice updates one record instead of adding a second.
 *
 * The whole read and parse runs on [Dispatchers.IO], and so does the write:
 * the XML parse and the store write both belong there, and the notice is a
 * value the UI collects afterwards.
 */
internal class TrackShareImport(
    private val tracks: () -> TrackAccess,
    private val log: (String) -> Unit = { message -> Log.i(TRACK_SHARE_TAG, message) },
) {
    private val state = MutableStateFlow<TrackShareState?>(null)
    private val pendingState = MutableStateFlow<PendingTrackShare?>(null)
    private val sequence = AtomicLong(0L)

    @Volatile
    private var imported: StoredTrack? = null

    val notice: StateFlow<TrackShareState?> = state.asStateFlow()

    /** The parsed share waiting for its confirm sheet, or null. */
    val confirmation: StateFlow<PendingTrackShare?> = pendingState.asStateFlow()

    /**
     * Reads and parses the document at [uri] and publishes it for the confirm
     * sheet. A failure publishes the notice it always did and returns null.
     */
    suspend fun prepare(
        uri: String,
        reader: SharedDocumentReader,
    ): PendingTrackShare? =
        withContext(Dispatchers.IO) {
            // A new share supersedes the notice and any sheet the previous one opened.
            state.value = null
            pendingState.value = null
            val document =
                when (val read = reader.read(uri)) {
                    is Outcome.Success -> {
                        read.value
                    }

                    is Outcome.Failure -> {
                        publishFailure(read.error, uri)
                        return@withContext null
                    }
                }
            val name = TrackShareIntent.fileName(document.displayName, uri, document.mimeType)
            val preview =
                when (val parsed = tracks().preview(name, document.bytes, CancellationHandle.None)) {
                    is Outcome.Success -> {
                        parsed.value
                    }

                    is Outcome.Failure -> {
                        publishFailure(parsed.error, name)
                        return@withContext null
                    }
                }
            PendingTrackShare(fileName = name, trackCount = preview.trackCount, shared = document)
                .also { share -> pendingState.value = share }
        }

    /** Imports the document the sheet confirmed and publishes its notice. */
    suspend fun confirm(): TrackShareState? {
        val share = pendingState.value ?: return null
        pendingState.value = null
        return withContext(Dispatchers.IO) {
            when (val importedFile = tracks().import(share.fileName, share.document().bytes, CancellationHandle.None)) {
                is Outcome.Success -> publishImported(share.fileName, importedFile.value.tracks, importedFile.value.warnings.size)
                is Outcome.Failure -> publishFailure(importedFile.error, share.fileName)
            }
        }
    }

    /** Drops the document the sheet was showing; nothing was stored and nothing is said. */
    fun cancel() {
        pendingState.value = null
    }

    /** Opens the analysis of the newest imported track, where T-0415 has no library yet. */
    fun openImported() {
        val track = imported ?: return
        TrackAnalysisSession.show(track)
        state.value = null
    }

    /** Clears the notice only while it is still the one [sequence] named. */
    fun dismiss(sequence: Long) {
        if (state.value?.sequence == sequence) {
            state.value = null
        }
    }

    private fun publishImported(
        name: String,
        records: List<StoredTrack>,
        skipped: Int,
    ): TrackShareState {
        imported = records.firstOrNull()
        log(
            "share import name=\"$name\" tracks=${records.size} skipped=$skipped " +
                "ids=${records.joinToString(separator = ",") { record -> record.id.value.take(ID_PREFIX) }}",
        )
        return publish(TrackImportOutcome(names = records.map { record -> record.name }, skipped = skipped))
    }

    private fun publishFailure(
        error: AppError,
        source: String,
    ): TrackShareState? {
        if (error == AppError.Cancelled) {
            return null
        }
        imported = null
        log("share import source=\"$source\" failed=$error")
        return publish(TrackImportOutcome(failure = trackImportFailure(error)))
    }

    private fun publish(outcome: TrackImportOutcome): TrackShareState =
        TrackShareState(outcome = outcome, sequence = sequence.incrementAndGet()).also { state.value = it }

    private companion object {
        const val ID_PREFIX = 12
    }
}

/**
 * The notice's reason for a typed import failure (T-0416). A document the app
 * refused or could not parse is not a track; a URI it could not open at all is
 * an unreadable file. Both keep the failure visible instead of silent.
 */
internal fun trackImportFailure(error: AppError): TrackImportFailure =
    when (error) {
        AppError.Unsupported, AppError.InvalidInput, AppError.CorruptData, is AppError.PluginFailed -> {
            TrackImportFailure.NotATrack
        }

        else -> {
            TrackImportFailure.Unreadable
        }
    }
