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
 * a `content:` or `file:` URI from the stream extra, or from the data URI a
 * sender uses instead of the extra. A `geo:` or `https:` link is never a
 * document, which is why [ACTION_VIEW] can share this rule with `ACTION_SEND`
 * without touching the link path.
 */
internal object TrackShareIntent {
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_VIEW = "android.intent.action.VIEW"

    private val DOCUMENT_SCHEMES = setOf("content", "file")
    private const val GPX_MIME = "application/gpx+xml"
    private const val GPX_EXTENSION = "gpx"
    private const val UNNAMED_DOCUMENT = "shared"

    /** The document URI a share carries, or null when the intent is not a document share. */
    fun documentUri(
        action: String?,
        streamUri: String?,
        dataUri: String?,
    ): String? {
        if (action != ACTION_SEND && action != ACTION_VIEW) {
            return null
        }
        val uri = streamUri?.takeIf { it.isNotBlank() } ?: dataUri?.takeIf { it.isNotBlank() } ?: return null
        val scheme = uri.substringBefore(':', missingDelimiterValue = "").lowercase()
        return uri.takeIf { scheme in DOCUMENT_SCHEMES }
    }

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
 * It reads the URI through a [SharedDocumentReader], imports the bytes through
 * the same [TrackAccess] the library uses, and publishes the notice the map
 * draws. The document hash is the track id, so sharing the same file twice
 * updates one record instead of adding a second.
 *
 * The whole import runs on [Dispatchers.IO]: the XML parse and the store write
 * both belong there, and the notice is a value the UI collects afterwards.
 */
internal class TrackShareImport(
    private val tracks: () -> TrackAccess,
    private val log: (String) -> Unit = { message -> Log.i(TRACK_SHARE_TAG, message) },
) {
    private val state = MutableStateFlow<TrackShareState?>(null)
    private val sequence = AtomicLong(0L)

    @Volatile
    private var imported: StoredTrack? = null

    val notice: StateFlow<TrackShareState?> = state.asStateFlow()

    /** Imports the document at [uri] and publishes its notice; a cancellation publishes nothing. */
    suspend fun import(
        uri: String,
        reader: SharedDocumentReader,
    ): TrackShareState? =
        withContext(Dispatchers.IO) {
            val document =
                when (val read = reader.read(uri)) {
                    is Outcome.Success -> {
                        read.value
                    }

                    is Outcome.Failure -> {
                        return@withContext publishFailure(read.error, uri)
                    }
                }
            val name = TrackShareIntent.fileName(document.displayName, uri, document.mimeType)
            when (val importedFile = tracks().import(name, document.bytes, CancellationHandle.None)) {
                is Outcome.Success -> publishImported(name, importedFile.value.tracks, importedFile.value.warnings.size)
                is Outcome.Failure -> publishFailure(importedFile.error, name)
            }
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
        return publish(TrackImportOutcome(failure = failureOf(error)))
    }

    private fun publish(outcome: TrackImportOutcome): TrackShareState =
        TrackShareState(outcome = outcome, sequence = sequence.incrementAndGet()).also { state.value = it }

    private fun failureOf(error: AppError): TrackImportFailure =
        when (error) {
            AppError.Unsupported, AppError.InvalidInput, AppError.CorruptData, is AppError.PluginFailed -> {
                TrackImportFailure.NotATrack
            }

            else -> {
                TrackImportFailure.Unreadable
            }
        }

    private companion object {
        const val ID_PREFIX = 12
    }
}
