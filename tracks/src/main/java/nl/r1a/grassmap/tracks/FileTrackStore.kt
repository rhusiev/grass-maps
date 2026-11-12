package nl.r1a.grassmap.tracks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.MapTrack
import nl.r1a.grassmap.plugin.api.model.TrackStyle
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The track library on disk (T-0401), under one directory the app owns:
 *
 * - `<hash>.gpx` - the imported document exactly as it arrived, named by its
 *   SHA-256, so importing the same file again targets the same file;
 * - `<id>.json` - the sidecar beside it: styling, cached analysis, the document
 *   index and the document hash it belongs to. The id is `hash` for a one-track
 *   document and `hash:<index>` for each track of a multi-track one.
 *
 * A write goes to a hidden sibling, is flushed and moved over the target
 * atomically, so a failed write leaves the previous file intact. A missing or
 * corrupt sidecar, a document whose bytes no longer match its hash, or a file
 * the reader rejects is skipped and logged; the rest of the library still
 * loads. No call throws, and file I/O runs on [Dispatchers.IO].
 *
 * The store is the single writer. [reload] re-reads the directory after another
 * component replaced it, such as a backup restore.
 */
class FileTrackStore(
    private val root: File,
    private val reader: TrackDocumentReader,
    private val analyzer: TrackAnalyzer = TrackAnalyzer(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) : TrackStore {
    private val mutex = Mutex()
    private val records = LinkedHashMap<TrackId, StoredTrack>()
    private val sidecars = LinkedHashMap<TrackId, TrackSidecar>()
    private var loaded = false
    private val drawnState = MutableStateFlow<List<MapTrack>>(emptyList())

    override val drawn: StateFlow<List<MapTrack>> = drawnState.asStateFlow()

    override suspend fun save(
        track: StoredTrack,
        source: ByteArray?,
    ): Outcome<StoredTrack> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                load()
                if (!track.style.isValid() || !TrackIdentity.isWellFormed(track.id)) {
                    return@withContext Outcome.Failure(AppError.InvalidInput)
                }
                val documentHash = TrackIdentity.documentHashOf(track.id)
                val existing = sidecars[track.id]
                val document = File(root, documentFileName(documentHash))
                if (source != null) {
                    if (TrackIdentity.documentHash(source) != documentHash) {
                        return@withContext Outcome.Failure(AppError.InvalidInput)
                    }
                    if (!writeAtomically(document, source)) {
                        return@withContext writeFailure("cannot write $document")
                    }
                } else if (!document.isFile) {
                    return@withContext Outcome.Failure(AppError.InvalidInput)
                }
                val effective = track.copy(style = existing?.style ?: track.style)
                val sidecar =
                    TrackSidecar(
                        id = track.id.value,
                        documentHash = documentHash,
                        trackIndex = track.trackIndex,
                        name = track.name,
                        savedAtMillis = clock(),
                        style = effective.style,
                        analysis = effective.analysis,
                    )
                if (!writeAtomically(sidecarFile(track.id), TrackSidecarJson.encode(sidecar).toByteArray())) {
                    return@withContext writeFailure("cannot write the sidecar of ${track.id.value}")
                }
                records[track.id] = effective
                sidecars[track.id] = sidecar
                publish()
                Outcome.Success(effective)
            }
        }

    override suspend fun get(id: TrackId): Outcome<StoredTrack> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                load()
                records[id]?.let { Outcome.Success(it) } ?: Outcome.Failure(AppError.NotFound)
            }
        }

    override suspend fun all(): List<StoredTrack> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                load()
                records.values.sortedBy { record -> record.id.value }
            }
        }

    override suspend fun summaries(): List<TrackSummary> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                load()
                sidecars.values.sortedBy { sidecar -> sidecar.id }.map { sidecar ->
                    TrackSummary(
                        id = TrackId(sidecar.id),
                        name = sidecar.name,
                        style = sidecar.style,
                        analysis = sidecar.analysis,
                        savedAtMillis = sidecar.savedAtMillis,
                        trackIndex = sidecar.trackIndex,
                    )
                }
            }
        }

    override suspend fun setStyle(
        id: TrackId,
        style: TrackStyle,
    ): Outcome<Unit> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                load()
                if (!style.isValid()) {
                    return@withContext Outcome.Failure(AppError.InvalidInput)
                }
                val record = records[id] ?: return@withContext Outcome.Failure(AppError.NotFound)
                val sidecar = sidecars[id] ?: return@withContext Outcome.Failure(AppError.NotFound)
                val updated = sidecar.copy(style = style)
                if (!writeAtomically(sidecarFile(id), TrackSidecarJson.encode(updated).toByteArray())) {
                    return@withContext writeFailure("cannot rewrite the sidecar of ${id.value}")
                }
                records[id] = record.copy(style = style)
                sidecars[id] = updated
                publish()
                Outcome.Success(Unit)
            }
        }

    override suspend fun remove(id: TrackId): Outcome<Unit> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                load()
                val sidecar = sidecars[id] ?: return@withContext Outcome.Failure(AppError.NotFound)
                val file = sidecarFile(id)
                if (file.exists() && !file.delete()) {
                    // The sidecar is what names the track, so a failed delete
                    // would bring it back on the next reload. Report it instead.
                    return@withContext writeFailure("cannot delete the sidecar of ${id.value}")
                }
                sidecars.remove(id)
                records.remove(id)
                val documentStillUsed = sidecars.values.any { it.documentHash == sidecar.documentHash }
                if (!documentStillUsed) {
                    val document = File(root, documentFileName(sidecar.documentHash))
                    if (document.exists() && !document.delete()) {
                        log("track ${id.value}: the document could not be deleted")
                    }
                }
                publish()
                Outcome.Success(Unit)
            }
        }

    override suspend fun reload(): Outcome<Unit> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                records.clear()
                sidecars.clear()
                loaded = false
                load()
                publish()
                Outcome.Success(Unit)
            }
        }

    /** Reads every sidecar and its document. A broken pair is skipped, not fatal. */
    private suspend fun load() {
        if (loaded) {
            return
        }
        val files =
            root
                .listFiles()
                ?.filter { it.isFile && it.name.endsWith(SIDECAR_SUFFIX) && !it.name.startsWith(TEMPORARY_PREFIX) }
                .orEmpty()
                .sortedBy { it.name }
        for (file in files) {
            val sidecar = readSidecar(file) ?: continue
            val id = TrackId(sidecar.id)
            if (file.name != sidecarFileName(id)) {
                log("track ${sidecar.id}: the sidecar name does not match its id")
                continue
            }
            val document = File(root, documentFileName(sidecar.documentHash))
            val bytes =
                try {
                    document.takeIf { it.isFile }?.readBytes()
                } catch (exception: IOException) {
                    log("track ${sidecar.id}: the document is unreadable")
                    null
                } ?: continue
            if (TrackIdentity.documentHash(bytes) != sidecar.documentHash) {
                log("track ${sidecar.id}: the document hash does not match the sidecar")
                continue
            }
            val tracks =
                when (val read = reader.read(bytes)) {
                    is Outcome.Success -> {
                        read.value
                    }

                    is Outcome.Failure -> {
                        log("track ${sidecar.id}: the document could not be read (${read.error})")
                        continue
                    }
                }
            val track = tracks.getOrNull(sidecar.trackIndex) ?: continue
            val analysis = analyzer.analyze(track)
            records[id] =
                StoredTrack(
                    id = id,
                    name = sidecar.name,
                    track = track,
                    analysis = analysis,
                    style = sidecar.style,
                    trackIndex = sidecar.trackIndex,
                )
            sidecars[id] = sidecar
            if (analysis != sidecar.analysis) {
                rewriteAnalysis(id, sidecar, analysis)
            }
        }
        loaded = true
    }

    /**
     * The document is parsed here anyway, so the analysis is derived from it
     * rather than trusted from the cache. A sidecar from an older build, or
     * one written under another policy, is brought up to date once.
     */
    private fun rewriteAnalysis(
        id: TrackId,
        sidecar: TrackSidecar,
        analysis: TrackAnalysis,
    ) {
        val updated = sidecar.copy(analysis = analysis)
        sidecars[id] = updated
        if (!writeAtomically(sidecarFile(id), TrackSidecarJson.encode(updated).toByteArray())) {
            log("track ${id.value}: the sidecar could not be updated with its statistics")
        }
    }

    private fun readSidecar(file: File): TrackSidecar? {
        val text =
            try {
                file.readText()
            } catch (exception: IOException) {
                log("sidecar ${file.name}: unreadable")
                return null
            } catch (exception: SecurityException) {
                log("sidecar ${file.name}: unreadable")
                return null
            }
        return TrackSidecarJson.decode(text) ?: run {
            log("sidecar ${file.name}: unreadable")
            null
        }
    }

    private fun publish() {
        drawnState.value = records.values.sortedBy { it.id.value }.map { it.toMapTrack() }
    }

    private fun writeAtomically(
        target: File,
        bytes: ByteArray,
    ): Boolean {
        val parent = target.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) {
            return false
        }
        val temporary = File(parent, TEMPORARY_PREFIX + target.name + TEMPORARY_SUFFIX)
        return try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        } catch (exception: IOException) {
            temporary.delete()
            log("cannot write $target: ${exception.message}")
            false
        } catch (exception: SecurityException) {
            temporary.delete()
            log("cannot write $target: ${exception.message}")
            false
        }
    }

    private fun sidecarFile(id: TrackId): File = File(root, sidecarFileName(id))

    private fun writeFailure(detail: String): Outcome.Failure {
        log(detail)
        return Outcome.Failure(AppError.Internal(ERROR_ID, detail))
    }

    private companion object {
        const val SIDECAR_SUFFIX = ".json"
        const val DOCUMENT_SUFFIX = ".gpx"
        const val TEMPORARY_PREFIX = "."
        const val TEMPORARY_SUFFIX = ".part"
        const val ERROR_ID = "tracks.store"

        fun sidecarFileName(id: TrackId): String = id.value + SIDECAR_SUFFIX

        fun documentFileName(documentHash: String): String = documentHash + DOCUMENT_SUFFIX
    }
}
