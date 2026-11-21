package nl.r1a.grassmaps.tracks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.MapTrack
import nl.r1a.grassmaps.plugin.api.model.TrackKind
import nl.r1a.grassmaps.plugin.api.model.TrackStyle
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
 *   index, the record kind and the document hash it belongs to. The id is the
 *   document hash for the document's first record and `hash:<index>` for each
 *   later one, so a record keeps its id when a build starts reading another
 *   element family; a sidecar an older build named `hash:0` is moved to the
 *   bare hash on the first load;
 * - `<id>.route.json` - the recompute sidecar of a saved route (T-0411): the
 *   profile, the three-step options and the waypoint chain. It is a second file
 *   on purpose. Losing it costs the recompute, never the track, so a route
 *   whose sidecar is missing or unreadable opens with the line it was saved
 *   with.
 *
 * A record is a `<trk>` or a `<rte>`: the library sidecar's `kind` says which,
 * and a reload reads the element family it names. The document's `<wpt>`
 * entries are read again from the document on every load, so an export writes
 * them back without a cached copy that can drift.
 *
 * A write goes to a hidden sibling, is flushed and moved over the target
 * atomically, so a failed write leaves the previous file intact. A missing or
 * corrupt sidecar, a document whose bytes no longer match its hash, or a file
 * the reader rejects is skipped and logged; the rest of the library still
 * loads. Removing a record deletes its document only when no sidecar on disk
 * still names it, including one a load skipped, and the trash undo restores a
 * document whichever of its records is undone first. No call throws, and file
 * I/O runs on [Dispatchers.IO].
 *
 * The store is the single writer. [reload] re-reads the directory after another
 * component replaced it, such as a backup restore.
 *
 * The document keeps the heights as it arrived, so a loaded track is corrected
 * again from [elevationCorrection] before its profile is recomputed (T-0403).
 * The correction is derived from the DEM mounted now, not persisted, and a
 * changed DEM therefore shows up on the next load. The first load of a library
 * therefore samples the DEM once per point without a height.
 */
class FileTrackStore(
    private val root: File,
    private val reader: TrackDocumentReader,
    private val analyzer: TrackAnalyzer = TrackAnalyzer(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val elevationCorrection: TrackElevationCorrection = TrackElevationCorrection(),
) : TrackStore {
    private val mutex = Mutex()
    private val records = LinkedHashMap<TrackId, StoredTrack>()
    private val sidecars = LinkedHashMap<TrackId, TrackSidecar>()
    private var loaded = false
    private val drawnState = MutableStateFlow<List<MapTrack>>(emptyList())
    private val revisionState = MutableStateFlow(0L)

    override val drawn: StateFlow<List<MapTrack>> = drawnState.asStateFlow()

    override val revision: StateFlow<Long> = revisionState.asStateFlow()

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
                // An existing record keeps the user's name, description and
                // folder, exactly as it keeps the style: re-importing the same
                // document must not undo a rename or move (T-0414). It keeps
                // the recompute too, because an imported document carries none
                // and the same id means the same line: dropping it would lose
                // the ability to recompute a route the user exported and
                // opened again.
                val effective =
                    track.copy(
                        style = existing?.style ?: track.style,
                        name = existing?.name ?: track.name,
                        description = existing?.description ?: track.description,
                        listId = existing?.listId ?: track.listId,
                        hidden = existing?.hidden ?: track.hidden,
                        recompute = track.recompute ?: records[track.id]?.recompute,
                    )
                val start = effective.track.startPoint()
                val sidecar =
                    TrackSidecar(
                        id = track.id.value,
                        documentHash = documentHash,
                        trackIndex = track.trackIndex,
                        name = effective.name,
                        savedAtMillis = clock(),
                        style = effective.style,
                        analysis = effective.analysis,
                        description = effective.description,
                        listId = effective.listId,
                        startLatitude = start?.latitude,
                        startLongitude = start?.longitude,
                        kind = effective.kind,
                        hidden = effective.hidden,
                    )
                if (!writeAtomically(sidecarFile(track.id), TrackSidecarJson.encode(sidecar).toByteArray())) {
                    return@withContext writeFailure("cannot write the sidecar of ${track.id.value}")
                }
                if (!writeRecompute(track.id, effective.recompute)) {
                    return@withContext writeFailure("cannot write the route sidecar of ${track.id.value}")
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

    override suspend fun document(id: TrackId): Outcome<ByteArray> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                load()
                val sidecar = sidecars[id] ?: return@withContext Outcome.Failure(AppError.NotFound)
                val document = File(root, documentFileName(sidecar.documentHash))
                try {
                    if (!document.isFile) {
                        Outcome.Failure(AppError.NotFound)
                    } else {
                        Outcome.Success(document.readBytes())
                    }
                } catch (exception: IOException) {
                    Outcome.Failure(AppError.Internal(ERROR_ID, exception.javaClass.simpleName))
                } catch (exception: SecurityException) {
                    Outcome.Failure(AppError.Internal(ERROR_ID, exception.javaClass.simpleName))
                }
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
                        description = sidecar.description,
                        listId = sidecar.listId,
                        startLatitude = sidecar.startLatitude,
                        startLongitude = sidecar.startLongitude,
                        kind = sidecar.kind,
                        hidden = sidecar.hidden,
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

    override suspend fun setHidden(
        id: TrackId,
        hidden: Boolean,
    ): Outcome<Unit> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                load()
                val record = records[id] ?: return@withContext Outcome.Failure(AppError.NotFound)
                val sidecar = sidecars[id] ?: return@withContext Outcome.Failure(AppError.NotFound)
                if (sidecar.hidden == hidden) {
                    return@withContext Outcome.Success(Unit)
                }
                val updated = sidecar.copy(hidden = hidden)
                if (!writeAtomically(sidecarFile(id), TrackSidecarJson.encode(updated).toByteArray())) {
                    return@withContext writeFailure("cannot rewrite the sidecar of ${id.value}")
                }
                records[id] = record.copy(hidden = hidden)
                sidecars[id] = updated
                publish()
                Outcome.Success(Unit)
            }
        }

    override suspend fun setMetadata(
        id: TrackId,
        metadata: TrackMetadata,
    ): Outcome<Unit> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                load()
                if (!metadata.isValid()) {
                    return@withContext Outcome.Failure(AppError.InvalidInput)
                }
                val record = records[id] ?: return@withContext Outcome.Failure(AppError.NotFound)
                val sidecar = sidecars[id] ?: return@withContext Outcome.Failure(AppError.NotFound)
                val cleaned = metadata.cleaned()
                val updated =
                    sidecar.copy(
                        name = cleaned.name,
                        description = cleaned.description,
                        listId = cleaned.listId,
                    )
                if (!writeAtomically(sidecarFile(id), TrackSidecarJson.encode(updated).toByteArray())) {
                    return@withContext writeFailure("cannot rewrite the sidecar of ${id.value}")
                }
                records[id] =
                    record.copy(
                        name = cleaned.name,
                        description = cleaned.description,
                        listId = cleaned.listId,
                    )
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
                deleteRecompute(id)
                sidecars.remove(id)
                records.remove(id)
                if (!documentStillNeeded(sidecar.documentHash)) {
                    val document = File(root, documentFileName(sidecar.documentHash))
                    if (document.exists() && !document.delete()) {
                        log("track ${id.value}: the document could not be deleted")
                    }
                }
                publish()
                Outcome.Success(Unit)
            }
        }

    override suspend fun removeToTrash(id: TrackId): Outcome<TrackRemoval> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                load()
                val sidecar = sidecars[id] ?: return@withContext Outcome.Failure(AppError.NotFound)
                val token = TRASH_PREFIX + id.value.replace(':', '_') + "-" + clock()
                val directory = trashDirectory(token)
                if (!directory.isDirectory && !directory.mkdirs()) {
                    return@withContext writeFailure("cannot create the trash directory for ${id.value}")
                }
                if (!TrackTrash.markDeleted(directory, clock())) {
                    log("track ${id.value}: the delete time could not be recorded; the directory time is used")
                }
                val sidecarFile = sidecarFile(id)
                if (!moveFile(sidecarFile, File(directory, sidecarFile.name))) {
                    return@withContext writeFailure("cannot park the sidecar of ${id.value}")
                }
                parkRecompute(id, directory)
                records.remove(id)
                sidecars.remove(id)
                if (!documentStillNeeded(sidecar.documentHash)) {
                    val document = File(root, documentFileName(sidecar.documentHash))
                    if (document.exists() && !moveFile(document, File(directory, document.name))) {
                        log("track ${id.value}: the document could not be parked")
                    }
                }
                publish()
                Outcome.Success(TrackRemoval(token))
            }
        }

    override suspend fun restoreFromTrash(removal: TrackRemoval): Outcome<Unit> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                load()
                val directory = trashDirectory(removal.token)
                if (!directory.isDirectory) {
                    return@withContext Outcome.Failure(AppError.NotFound)
                }
                // The undo is a week, measured from the delete time. A receipt
                // older than that answers NotFound even before the purge runs,
                // so a late undo can never restore a file the purge removed.
                if (TrackTrash.isExpired(directory, clock())) {
                    return@withContext Outcome.Failure(AppError.NotFound)
                }
                val parkedSidecars =
                    directory
                        .listFiles()
                        ?.filter { it.isFile && it.name.endsWith(SIDECAR_SUFFIX) && !it.name.endsWith(ROUTE_SIDECAR_SUFFIX) }
                        .orEmpty()
                val sidecarText =
                    parkedSidecars.singleOrNull()?.let { file ->
                        try {
                            file.readText()
                        } catch (exception: IOException) {
                            null
                        } catch (exception: SecurityException) {
                            null
                        }
                    } ?: return@withContext Outcome.Failure(AppError.NotFound)
                val sidecar = TrackSidecarJson.decode(sidecarText) ?: return@withContext Outcome.Failure(AppError.CorruptData)
                val target = File(root, sidecarFileName(TrackId(sidecar.id)))
                if (target.exists()) {
                    // The same document was imported again after the delete.
                    // Restoring would overwrite that newer record, so the
                    // old receipt no longer applies.
                    return@withContext Outcome.Failure(AppError.InvalidInput)
                }
                // The document goes back first and the sidecar last: the
                // sidecar is what makes the track visible, so a failed move
                // leaves a receipt that can still be retried. Two records of
                // one document are parked by two deletes and the later delete
                // takes the document, so an undo of the earlier record must
                // look in the other tokens too, not only its own.
                val document = File(root, documentFileName(sidecar.documentHash))
                if (!document.exists()) {
                    val parkedDocument =
                        findParkedDocument(sidecar.documentHash, directory)
                            ?: return@withContext Outcome.Failure(AppError.NotFound)
                    if (!moveFile(parkedDocument, document)) {
                        return@withContext writeFailure("cannot restore the document of ${sidecar.id}")
                    }
                }
                if (!moveFile(parkedSidecars.single(), target)) {
                    return@withContext writeFailure("cannot restore the sidecar of ${sidecar.id}")
                }
                restoreRecompute(TrackId(sidecar.id), directory)
                TrackTrash.clearMarker(directory)
                // Only this token's own directory may go: a second delete has
                // its own parked token, and its undo must survive this one.
                if (directory.listFiles().orEmpty().isEmpty()) {
                    directory.delete()
                }
                records.clear()
                sidecars.clear()
                loaded = false
                load()
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
        val files = sidecarFiles()
        for (file in files) {
            val sidecar = readSidecar(file) ?: continue
            val storedId = TrackId(sidecar.id)
            if (file.name != sidecarFileName(storedId)) {
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
            val entities =
                when (val read = reader.read(bytes)) {
                    is Outcome.Success -> {
                        read.value
                    }

                    is Outcome.Failure -> {
                        log("track ${sidecar.id}: the document could not be read (${read.error})")
                        continue
                    }
                }
            val record = entities.records().getOrNull(sidecar.trackIndex) ?: continue
            // A record's kind is decided by the element family it came from,
            // so a sidecar that names a route for a track (or the other way)
            // is skipped instead of drawing one as the other.
            if (record.kind != sidecar.kind) {
                log("track ${sidecar.id}: the sidecar kind does not match the document")
                continue
            }
            // An older build appended its document index to the first record
            // of every multi-record document too. That record's id is the bare
            // hash now, so its sidecar is renamed once; without the rename a
            // re-import would add a second record instead of updating it.
            val id = canonicalId(storedId, sidecar, bytes)
            val corrected =
                when (sidecar.kind) {
                    TrackKind.Route -> CorrectedTrack(record.track, ElevationCorrectionReport(ElevationSource.NoDem))
                    TrackKind.Track -> elevationCorrection.correct(record.track)
                }
            val analysis = analyzer.analyze(corrected.track)
            if (corrected.report.changed) {
                log("track ${sidecar.id}: ${corrected.report.describe()}")
            }
            val start = corrected.track.startPoint()
            records[id] =
                StoredTrack(
                    id = id,
                    name = sidecar.name,
                    track = corrected.track,
                    analysis = analysis,
                    style = sidecar.style,
                    trackIndex = sidecar.trackIndex,
                    elevation = corrected.report,
                    description = sidecar.description,
                    listId = sidecar.listId,
                    kind = sidecar.kind,
                    hidden = sidecar.hidden,
                    recompute = readRecompute(id),
                    waypoints = entities.waypoints,
                )
            val refreshed =
                sidecar.copy(
                    analysis = analysis,
                    startLatitude = start?.latitude,
                    startLongitude = start?.longitude,
                )
            sidecars[id] = refreshed
            if (refreshed != sidecar) {
                rewriteSidecar(id, refreshed)
            }
        }
        loaded = true
    }

    /**
     * The document is parsed here anyway, so the analysis and the first point
     * are derived from it rather than trusted from the cache. A sidecar from an
     * older build, or one written under another policy, is brought up to date
     * once.
     */
    private fun rewriteSidecar(
        id: TrackId,
        sidecar: TrackSidecar,
    ) {
        if (!writeAtomically(sidecarFile(id), TrackSidecarJson.encode(sidecar).toByteArray())) {
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
        drawnState.value =
            records.values
                .sortedBy { it.id.value }
                .filterNot { record -> record.hidden }
                .map { it.toMapTrack() }
        revisionState.value = revisionState.value + 1
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

    /**
     * The id this build gives the record [sidecar] names, after moving a
     * sidecar an older build keyed with its document index. The old name stands
     * when the canonical file already exists or the move fails, so the record
     * still loads; the next load retries the rename.
     */
    private fun canonicalId(
        stored: TrackId,
        sidecar: TrackSidecar,
        bytes: ByteArray,
    ): TrackId {
        val canonical = TrackIdentity.of(bytes, sidecar.trackIndex)
        if (canonical == stored) {
            return stored
        }
        val target = sidecarFile(canonical)
        if (target.exists()) {
            log("track ${sidecar.id}: ${target.name} already exists; the older id is kept")
            return stored
        }
        if (!writeAtomically(target, TrackSidecarJson.encode(sidecar.copy(id = canonical.value)).toByteArray())) {
            log("track ${sidecar.id}: the sidecar could not be moved to ${canonical.value}")
            return stored
        }
        val source = sidecarFile(stored)
        if (!source.exists() || source.delete()) {
            log("track ${sidecar.id}: the sidecar was moved to ${canonical.value}")
            return canonical
        }
        target.delete()
        log("track ${sidecar.id}: the older sidecar name could not be removed")
        return stored
    }

    /** Every sidecar directly under the root, in name order. */
    private fun sidecarFiles(): List<File> =
        root
            .listFiles()
            .orEmpty()
            .filter {
                it.isFile && it.name.endsWith(SIDECAR_SUFFIX) && !it.name.endsWith(ROUTE_SIDECAR_SUFFIX) &&
                    !it.name.startsWith(TEMPORARY_PREFIX)
            }.sortedBy { it.name }

    /**
     * True when a sidecar on disk still names [documentHash]. Reading the
     * directory rather than [sidecars] matters: a record this load skipped - a
     * broken sidecar, a hash or kind mismatch - is not in the map, and deleting
     * its document would take the only copy of the geometry it would show.
     */
    private fun documentStillNeeded(documentHash: String): Boolean =
        sidecarFiles().any { file ->
            val text =
                try {
                    file.readText()
                } catch (exception: IOException) {
                    null
                } catch (exception: SecurityException) {
                    null
                }
            text != null && TrackSidecarJson.documentHashOf(text) == documentHash
        }

    /**
     * The parked copy of [documentHash]: this token's when it has one, else the
     * copy another token holds. Two records of one document are parked by two
     * deletes, and the later delete takes the document, so the undo of an
     * earlier record finds it in that token's directory.
     */
    private fun findParkedDocument(
        documentHash: String,
        own: File,
    ): File? {
        val name = documentFileName(documentHash)
        File(own, name).takeIf { it.isFile }?.let { return it }
        return File(root, TrackTrash.DIRECTORY)
            .listFiles()
            .orEmpty()
            .asSequence()
            .filter { directory -> directory.isDirectory && directory.path != own.path }
            .map { directory -> File(directory, name) }
            .firstOrNull { document -> document.isFile }
    }

    private fun recomputeFile(id: TrackId): File = File(root, recomputeFileName(id))

    /**
     * Writes the route's recompute record, or removes the file when the record
     * has none. A same-id save keeps the loaded record's recompute when the
     * incoming one is null, so a removal only clears a file the loaded record
     * could not use or one left without a record.
     */
    private fun writeRecompute(
        id: TrackId,
        recompute: PlannedRouteRecompute?,
    ): Boolean {
        val file = recomputeFile(id)
        if (recompute == null) {
            if (file.exists() && !file.delete()) {
                log("track ${id.value}: the stale route sidecar could not be deleted")
                return false
            }
            return true
        }
        return writeAtomically(file, RouteSidecarJson.encode(recompute).toByteArray())
    }

    /** The route's recompute record, or null when it is missing or unusable. */
    private fun readRecompute(id: TrackId): PlannedRouteRecompute? {
        val file = recomputeFile(id)
        if (!file.isFile) {
            return null
        }
        val text =
            try {
                file.readText()
            } catch (exception: IOException) {
                log("track ${id.value}: the route sidecar is unreadable")
                return null
            } catch (exception: SecurityException) {
                log("track ${id.value}: the route sidecar is unreadable")
                return null
            }
        return RouteSidecarJson.decode(text) ?: run {
            log("track ${id.value}: the route sidecar is not one this build reads")
            null
        }
    }

    private fun deleteRecompute(id: TrackId) {
        val file = recomputeFile(id)
        if (file.exists() && !file.delete()) {
            log("track ${id.value}: the route sidecar could not be deleted")
        }
    }

    /** Parks the route sidecar with its record so the undo restores both. */
    private fun parkRecompute(
        id: TrackId,
        directory: File,
    ) {
        val file = recomputeFile(id)
        if (!file.isFile) {
            return
        }
        if (!moveFile(file, File(directory, file.name))) {
            // The line and the sidecar's own metadata still restore; only the
            // recompute is lost, which is the same state as a missing sidecar.
            log("track ${id.value}: the route sidecar could not be parked")
        }
    }

    private fun restoreRecompute(
        id: TrackId,
        directory: File,
    ) {
        val parked = File(directory, recomputeFileName(id))
        if (!parked.isFile) {
            return
        }
        if (!moveFile(parked, recomputeFile(id))) {
            log("track ${id.value}: the route sidecar could not be restored")
        }
    }

    private fun trashDirectory(token: String): File = File(root, TrackTrash.DIRECTORY + "/" + token)

    private fun moveFile(
        from: File,
        to: File,
    ): Boolean =
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (exception: IOException) {
            log("cannot move $from to $to: ${exception.message}")
            false
        } catch (exception: SecurityException) {
            log("cannot move $from to $to: ${exception.message}")
            false
        }

    private fun writeFailure(detail: String): Outcome.Failure {
        log(detail)
        return Outcome.Failure(AppError.Internal(ERROR_ID, detail))
    }

    private companion object {
        const val SIDECAR_SUFFIX = ".json"
        const val ROUTE_SIDECAR_SUFFIX = ".route.json"
        const val DOCUMENT_SUFFIX = ".gpx"
        const val TEMPORARY_PREFIX = "."
        const val TEMPORARY_SUFFIX = ".part"
        const val ERROR_ID = "tracks.store"
        const val TRASH_PREFIX = "track-"

        fun sidecarFileName(id: TrackId): String = id.value + SIDECAR_SUFFIX

        fun recomputeFileName(id: TrackId): String = id.value + ROUTE_SIDECAR_SUFFIX

        fun documentFileName(documentHash: String): String = documentHash + DOCUMENT_SUFFIX
    }
}
