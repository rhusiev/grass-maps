package nl.r1a.grassmap.app

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nl.r1a.grassmap.app.backup.BackupCoordinator
import nl.r1a.grassmap.app.backup.BackupExport
import nl.r1a.grassmap.app.backup.BackupUiController
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Debug-only commands that open the backup screen and drive a bundle on the
 * host (T-0608/T-0609). The system file picker cannot be driven from a capture
 * script, so these write and read a file under the app's private cache
 * directory and go through the same coordinator the screen uses.
 */
internal val SHOW_BACKUP_COMMAND: CommandId = CommandId("core.backup.show")
internal val HIDE_BACKUP_COMMAND: CommandId = CommandId("core.backup.hide")
internal val EXPORT_BACKUP_COMMAND: CommandId = CommandId("core.backup.export_file")
internal val IMPORT_BACKUP_COMMAND: CommandId = CommandId("core.backup.import_file")

private val BACKUP_COMMAND_POLICY: CommandPolicy =
    CommandPolicy(
        undoable = false,
        allowlistedForControl = true,
        dispatcher = CommandDispatcher.IO,
    )

internal fun backupScreenCommands(
    backup: () -> BackupCoordinator,
    ui: () -> BackupUiController,
    captureRoot: () -> File,
): List<Command<*, *>> =
    listOf(
        ShowBackupCommand(ui),
        HideBackupCommand(),
        ExportBackupFileCommand(backup, ui, captureRoot),
        ImportBackupFileCommand(ui, captureRoot),
    )

private class ShowBackupCommand(
    private val ui: () -> BackupUiController,
) : Command<JsonElement, String>(
        id = SHOW_BACKUP_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Opens the backup screen.",
        policy = BACKUP_COMMAND_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        BackupScreenSession.show()
        ui().open()
        return Outcome.Success(CommandResult("visible=true"))
    }
}

private class HideBackupCommand :
    Command<JsonElement, String>(
        id = HIDE_BACKUP_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Closes the backup screen.",
        policy = BACKUP_COMMAND_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        BackupScreenSession.hide()
        return Outcome.Success(CommandResult("visible=false"))
    }
}

/** Writes the real export to `<files>/backup-captures/<name>.zip` and reports its counts. */
private class ExportBackupFileCommand(
    private val backup: () -> BackupCoordinator,
    private val ui: () -> BackupUiController,
    private val captureRoot: () -> File,
) : Command<JsonElement, String>(
        id = EXPORT_BACKUP_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Writes the export to the app's backup capture folder. Parameter: name.",
        policy = BACKUP_COMMAND_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val name = nameOf(params) ?: return Outcome.Failure(AppError.InvalidInput)
        val export =
            when (val exported = backup().export()) {
                is Outcome.Success -> exported.value
                is Outcome.Failure -> return exported
            }
        val root = captureRoot()
        if (!root.isDirectory && !root.mkdirs()) {
            return Outcome.Failure(AppError.Internal("app.backup.capture", "cannot create $root"))
        }
        val target = File(root, "$name.zip")
        val written = writeFile(target, export.bytes)
        if (written is Outcome.Failure) {
            return written
        }
        ui().exported(export)
        return Outcome.Success(CommandResult(describe(name, target, export)))
    }

    private fun describe(
        name: String,
        target: File,
        export: BackupExport,
    ): String =
        "name=$name bytes=${export.bytes.size} kinds=" +
            export.counts.joinToString(separator = ",") { count -> "${count.kind}:${count.count}" } +
            " path=${target.absolutePath}"

    private fun writeFile(
        target: File,
        bytes: ByteArray,
    ): Outcome<Unit> =
        try {
            FileOutputStream(target).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            Outcome.Success(Unit)
        } catch (exception: IOException) {
            Outcome.Failure(AppError.Internal("app.backup.capture", exception.javaClass.simpleName))
        }
}

/** Reads a capture bundle and runs the real import, which asks its conflicts on screen. */
private class ImportBackupFileCommand(
    private val ui: () -> BackupUiController,
    private val captureRoot: () -> File,
) : Command<JsonElement, String>(
        id = IMPORT_BACKUP_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Imports a bundle from the app's backup capture folder. Parameter: name.",
        policy = BACKUP_COMMAND_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val name = nameOf(params) ?: return Outcome.Failure(AppError.InvalidInput)
        val file = File(captureRoot(), "$name.zip")
        if (!file.isFile) {
            return Outcome.Failure(AppError.NotFound)
        }
        return try {
            ui().startImport(file.inputStream())
            Outcome.Success(CommandResult("importing name=$name"))
        } catch (exception: IOException) {
            Outcome.Failure(AppError.Internal("app.backup.capture", exception.javaClass.simpleName))
        }
    }
}

private fun nameOf(params: JsonElement): String? {
    val json = params as? JsonObject ?: return null
    return (json["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { name ->
        name.isNotEmpty() && name.none { character -> character == '/' || character == '\\' || character == '.' }
    }
}
