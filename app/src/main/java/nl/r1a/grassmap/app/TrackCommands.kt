package nl.r1a.grassmap.app

import android.util.Log
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.tracks.StoredTrack
import java.io.File
import java.io.IOException
import java.util.Locale

/** The debug trigger that proves the tracks seam. */
internal val TRACK_IMPORT_COMMAND: CommandId = CommandId("core.track.import_test")

private const val TRACK_TAG = "GrassMapsTracks"
private const val DEFAULT_ASSET = "mock-walk.gpx"

/**
 * Imports one GPX from an asset name or an absolute path through the real
 * `file.gpx` plugin, stores it and logs the elevation profile. No UI: T-0401
 * and T-0414 own the library, and the control channel is the only caller for
 * now.
 */
internal class TrackImportTestCommand(
    private val tracks: () -> TrackAccess,
    private val assetReader: (String) -> ByteArray?,
    private val log: (String) -> Unit = { message -> Log.i(TRACK_TAG, message) },
) : Command<JsonElement, String>(
        id = TRACK_IMPORT_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Imports a GPX asset or path, stores the track and reports its elevation profile.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val path = pathOf(params) ?: DEFAULT_ASSET
        val bytes =
            when (val loaded = load(path)) {
                is Outcome.Success -> loaded.value
                is Outcome.Failure -> return loaded
            }
        val imported =
            when (val outcome = tracks().import(path, bytes, CancellationHandle.None)) {
                is Outcome.Success -> {
                    outcome.value
                }

                is Outcome.Failure -> {
                    log("import path=\"$path\" failed=${outcome.error}")
                    return outcome
                }
            }
        val stored = tracks().storedTracks().size
        val summary =
            imported.tracks.joinToString(separator = "\n") { record ->
                describe(record, stored, imported.warnings.size)
            }
        summary.lines().forEach { line -> log(line) }
        imported.warningLines().forEach { line -> log("warning $line") }
        return Outcome.Success(CommandResult(summary))
    }

    private fun describe(
        record: StoredTrack,
        stored: Int,
        warnings: Int,
    ): String {
        val analysis = record.analysis
        return "track id=${record.id.value.take(ID_PREFIX)} index=${record.trackIndex} " +
            "name=\"${record.name.orEmpty()}\" points=${analysis.pointCount} " +
            "distance_m=${meters(analysis.distanceMeters)} " +
            "ascent_m=${meters(analysis.ascentMeters)} descent_m=${meters(analysis.descentMeters)} " +
            "min_ele_m=${analysis.minElevationMeters?.let(::meters) ?: "none"} " +
            "max_ele_m=${analysis.maxElevationMeters?.let(::meters) ?: "none"} " +
            "duration_s=${analysis.durationMillis?.let(::seconds) ?: "none"} " +
            "moving_s=${analysis.movingTimeMillis?.let(::seconds) ?: "none"} " +
            "avg_speed_mps=${analysis.averageSpeedMetersPerSecond?.let(::speed) ?: "none"} " +
            "max_speed_mps=${analysis.maxSpeedMetersPerSecond?.let(::speed) ?: "none"} " +
            "stored=$stored warnings=$warnings"
    }

    private fun seconds(millis: Long): String = String.format(Locale.ROOT, "%.3f", millis / MILLIS_PER_SECOND)

    private fun speed(metersPerSecond: Double): String = String.format(Locale.ROOT, "%.3f", metersPerSecond)

    private fun load(path: String): Outcome<ByteArray> {
        if (path.startsWith("/")) {
            return try {
                val file = File(path)
                if (!file.isFile || file.length() <= 0L || file.length() > MAX_IMPORT_BYTES) {
                    log("path=\"$path\" rejected: not a bounded regular file")
                    return Outcome.Failure(AppError.InvalidInput)
                }
                Outcome.Success(file.readBytes())
            } catch (exception: IOException) {
                log("path=\"$path\" unreadable: ${exception.message}")
                Outcome.Failure(AppError.NotFound)
            } catch (exception: SecurityException) {
                log("path=\"$path\" unreadable: ${exception.message}")
                Outcome.Failure(AppError.NotFound)
            }
        }
        return assetReader(path)?.let { Outcome.Success(it) } ?: Outcome.Failure(AppError.NotFound)
    }

    private fun pathOf(params: JsonElement): String? {
        val json = params as? JsonObject ?: return null
        return (json[PARAM_PATH] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun meters(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

    private companion object {
        const val PARAM_PATH = "path"
        const val ID_PREFIX = 12
        const val MILLIS_PER_SECOND = 1_000.0
    }
}

internal fun trackCommands(
    tracks: () -> TrackAccess,
    assetReader: (String) -> ByteArray?,
): List<Command<*, *>> = listOf(TrackImportTestCommand(tracks, assetReader))

/** An imported file is untrusted input; the debug path gets the same bound. */
private const val MAX_IMPORT_BYTES: Long = 64L * 1024L * 1024L
