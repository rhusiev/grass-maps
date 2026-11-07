package nl.r1a.grassmap.plugin.api.provider

import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.FileEntityKind
import nl.r1a.grassmap.plugin.api.model.ImportResult
import nl.r1a.grassmap.plugin.api.model.ImportedEntities

/**
 * A file format behind the `file.format` contract. The static members let the
 * host choose a provider without activating it; [importFile] and [export] run
 * off the UI thread.
 *
 * [importFile] is the v1 `import` of the plugin spec. A partly readable
 * document is a success: the caller gets the entities that survived and one
 * warning per skipped part. Only a document that cannot be read at all is a
 * failure.
 */
interface FileFormat : Provider {
    /** Lowercase extensions without a dot, e.g. `gpx`. */
    val extensions: Set<String>

    /** MIME types the provider accepts, e.g. `application/gpx+xml`. */
    val mimeTypes: Set<String>

    /** The entity families the provider can read. */
    val entityKinds: Set<FileEntityKind>

    /** Reads [bytes] into entities, or returns a typed error. */
    suspend fun importFile(
        bytes: ByteArray,
        cancellation: CancellationHandle,
    ): Outcome<ImportResult>

    /**
     * Writes [entities]. A provider that cannot export yet returns
     * [AppError.Unsupported] rather than an empty document.
     */
    suspend fun export(
        entities: ImportedEntities,
        cancellation: CancellationHandle,
    ): Outcome<ByteArray> = Outcome.Failure(AppError.Unsupported)
}

object FileFormatContract {
    const val NAME: String = "file.format"
    const val VERSION: String = "1.0.0"

    val REFERENCE: ProviderContract = ProviderContract(NAME, VERSION)
}
