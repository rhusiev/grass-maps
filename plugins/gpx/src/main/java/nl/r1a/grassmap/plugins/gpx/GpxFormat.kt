package nl.r1a.grassmap.plugins.gpx

import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.FileEntityKind
import nl.r1a.grassmap.plugin.api.model.ImportResult
import nl.r1a.grassmap.plugin.api.model.ImportedEntities
import nl.r1a.grassmap.plugin.api.provider.FileFormat
import nl.r1a.grassmap.plugin.api.provider.FileFormatContract
import nl.r1a.grassmap.plugin.api.provider.ProviderContract

/**
 * GPX 1.0 and 1.1 behind the `file.format` contract. Reading is lenient: a
 * point with unusable coordinates is skipped and reported, so the rest of the
 * document still imports. Export writes GPX 1.1 and keeps a stored track's
 * style in the `grassmap` extension namespace (T-0410).
 */
internal class GpxFormat : FileFormat {
    override val contract: ProviderContract = FileFormatContract.REFERENCE

    override val extensions: Set<String> = setOf(EXTENSION)

    override val mimeTypes: Set<String> = setOf(MIME_TYPE, GENERIC_XML_MIME_TYPE)

    override val entityKinds: Set<FileEntityKind> =
        setOf(FileEntityKind.Track, FileEntityKind.Route, FileEntityKind.Waypoint)

    override suspend fun importFile(
        bytes: ByteArray,
        cancellation: CancellationHandle,
    ): Outcome<ImportResult> = GpxParser.parse(bytes, cancellation)

    override suspend fun export(
        entities: ImportedEntities,
        cancellation: CancellationHandle,
    ): Outcome<ByteArray> = GpxWriter.write(entities, cancellation)

    private companion object {
        const val EXTENSION = "gpx"
        const val MIME_TYPE = "application/gpx+xml"
        const val GENERIC_XML_MIME_TYPE = "application/xml"
    }
}
