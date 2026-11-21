package nl.r1a.grassmaps.app.backup

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmaps.app.TrackAccess
import nl.r1a.grassmaps.core.services.backup.BackupKinds
import nl.r1a.grassmaps.core.services.backup.BundleEntry
import nl.r1a.grassmaps.core.services.backup.BundleFile
import nl.r1a.grassmaps.core.services.backup.BundleKindSource
import nl.r1a.grassmaps.core.services.backup.sha256Hex
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.TrackStyle
import nl.r1a.grassmaps.tracks.StoredTrack
import nl.r1a.grassmaps.tracks.TrackId
import nl.r1a.grassmaps.tracks.TrackMetadata

/**
 * The track library as one bundle kind (T-0608): each record's GPX document
 * exactly as the store keeps it, plus the sidecar fields the document does not
 * carry (name, description, folder, colour, width and the map flag).
 *
 * The document is the identity: its bytes hash to the record id, so exporting
 * the stored document and importing it again is the same record, not a copy.
 */
internal class TrackBundleKind(
    private val tracks: () -> TrackAccess,
) : BundleKindSource {
    override val kind: String = BackupKinds.TRACKS

    override suspend fun count(): Outcome<Int> = Outcome.Success(tracks().storedTracks().size)

    override suspend fun read(): Outcome<List<BundleEntry>> {
        val entries = mutableListOf<BundleEntry>()
        for (record in tracks().storedTracks()) {
            val document =
                when (val read = tracks().document(record.id)) {
                    is Outcome.Success -> read.value
                    is Outcome.Failure -> return read
                }
            val hash = sha256Hex(document)
            if (record.id.value.substringBefore(':') != hash) {
                return Outcome.Failure(AppError.CorruptData)
            }
            entries +=
                BundleEntry(
                    id = record.id.value,
                    value = valueOf(record, hash),
                    files = listOf(BundleFile(fileName(record.id), document)),
                )
        }
        return Outcome.Success(entries)
    }

    override suspend fun apply(entries: List<BundleEntry>): Outcome<Unit> {
        val byDocument = mutableMapOf<String, MutableList<BundleEntry>>()
        for (entry in entries) {
            val hash = entry.value.stringValue(HASH_KEY) ?: return Outcome.Failure(AppError.InvalidInput)
            byDocument.getOrPut(hash) { mutableListOf() } += entry
        }
        for ((hash, group) in byDocument) {
            val document =
                group.firstNotNullOfOrNull { entry -> entry.files.firstOrNull() }?.bytes
                    ?: return Outcome.Failure(AppError.InvalidInput)
            if (sha256Hex(document) != hash) {
                return Outcome.Failure(AppError.InvalidInput)
            }
            val allStored =
                group.all { entry ->
                    tracks().stored(TrackId(entry.id)) is Outcome.Success
                }
            if (!allStored) {
                val imported = tracks().import(fileName = "$hash.gpx", bytes = document)
                if (imported is Outcome.Failure) {
                    return imported
                }
            }
            for (entry in group) {
                val written = writeRecord(entry)
                if (written is Outcome.Failure) {
                    return written
                }
            }
        }
        return Outcome.Success(Unit)
    }

    private suspend fun writeRecord(entry: BundleEntry): Outcome<Unit> {
        val id = TrackId(entry.id)
        if (tracks().stored(id) is Outcome.Failure) {
            return Outcome.Failure(AppError.CorruptData)
        }
        val colour = entry.value.stringValue(COLOUR_KEY) ?: TrackStyle.DEFAULT_COLOUR
        val width = entry.value.floatValue(WIDTH_KEY) ?: TrackStyle.DEFAULT_WIDTH_DP
        val style = TrackStyle(colour, width)
        if (!style.isValid()) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        when (val styled = tracks().setStyle(id, style)) {
            is Outcome.Failure -> return styled
            is Outcome.Success -> Unit
        }
        val metadata =
            TrackMetadata(
                name = entry.value.stringValue(NAME_KEY),
                description = entry.value.stringValue(DESCRIPTION_KEY),
                listId = entry.value.stringValue(LIST_KEY),
            )
        when (val saved = tracks().setMetadata(id, metadata)) {
            is Outcome.Failure -> return saved
            is Outcome.Success -> Unit
        }
        return tracks().setHidden(id, entry.value.booleanValue(HIDDEN_KEY) == true)
    }

    private fun valueOf(
        record: StoredTrack,
        hash: String,
    ) = buildJsonObject {
        put(NAME_KEY, record.name)
        record.description?.let { put(DESCRIPTION_KEY, it) }
        record.listId?.let { put(LIST_KEY, it) }
        put(COLOUR_KEY, record.style.colour)
        put(WIDTH_KEY, record.style.widthDp)
        put(HIDDEN_KEY, record.hidden)
        put(KIND_KEY, record.kind.name)
        put(HASH_KEY, hash)
    }

    private fun fileName(id: TrackId): String = kind + "/" + id.value.replace(':', '-') + ".gpx"

    private companion object {
        const val NAME_KEY = "name"
        const val DESCRIPTION_KEY = "description"
        const val LIST_KEY = "listId"
        const val COLOUR_KEY = "colour"
        const val WIDTH_KEY = "widthDp"
        const val HIDDEN_KEY = "hidden"
        const val KIND_KEY = "kind"
        const val HASH_KEY = "documentSha256"
    }
}
