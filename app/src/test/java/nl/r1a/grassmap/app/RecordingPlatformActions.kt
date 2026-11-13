package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.DocumentSaveResult
import nl.r1a.grassmap.plugin.api.host.PlatformActions
import nl.r1a.grassmap.plugin.api.host.PlatformFile

/**
 * The `PlatformActions` fake the export tests record through, so no test starts
 * an Android picker or chooser. Each call is kept as one line, and a test can
 * script the result of a save or a share.
 */
internal class RecordingPlatformActions : PlatformActions {
    val calls = mutableListOf<String>()
    var saveResult: Outcome<DocumentSaveResult> = Outcome.Success(DocumentSaveResult.SAVED)
    var shareResult: Outcome<Unit> = Outcome.Success(Unit)

    override fun copyToClipboard(text: String): Outcome<Unit> = Outcome.Success(Unit)

    override fun shareText(
        text: String,
        subject: String?,
    ): Outcome<Unit> = Outcome.Success(Unit)

    override suspend fun shareFile(
        file: PlatformFile,
        subject: String?,
    ): Outcome<Unit> {
        calls += "file:${file.fileName}:${file.mimeType}:${file.bytes.size}"
        return shareResult
    }

    override suspend fun saveFile(file: PlatformFile): Outcome<DocumentSaveResult> {
        calls += "save:${file.fileName}:${file.mimeType}:${file.bytes.size}"
        return saveResult
    }

    override fun openUri(uri: String): Outcome<Unit> = Outcome.Success(Unit)
}
