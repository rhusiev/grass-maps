package nl.r1a.grassmap.plugins.valhalla

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException

/** How the pack stores its tiles; Valhalla accepts either one. */
internal sealed interface ValhallaTiles {
    /** A tar extract read through `mjolnir.tile_extract`. */
    data class Extract(
        val archive: File,
    ) : ValhallaTiles

    /** A tile directory read through `mjolnir.tile_dir`. */
    data class Directory(
        val directory: File,
    ) : ValhallaTiles
}

/** What the device holds, or why no engine can start from it. */
internal sealed interface ValhallaPackStatus {
    data class Ready(
        val directory: File,
        val tiles: ValhallaTiles,
    ) : ValhallaPackStatus

    data class NotReady(
        val problem: ValhallaPackProblem,
        val detail: String,
    ) : ValhallaPackStatus
}

/** The three ways a pack can fail, each with its own user-facing message. */
internal enum class ValhallaPackProblem {
    Absent,
    Incomplete,
    Unusable,
}

/** A pack the engine cannot start from. [detail] is for the log, not the user. */
internal class ValhallaPackException(
    val problem: ValhallaPackProblem,
    val detail: String,
    cause: Throwable? = null,
) : Exception(detail, cause)

/**
 * Reads what the host mounted, without touching the native engine.
 *
 * The host hands over a directory path as soon as external storage exists, so a
 * non-null path says nothing about the pack. Every route therefore inspects the
 * directory first, and the engine reports the exact problem instead of failing
 * inside the native constructor.
 */
internal object ValhallaPack {
    const val CONFIG_FILE = "valhalla.json"
    const val TILES_ARCHIVE = "tiles.tar"
    const val TILES_DIRECTORY = "tiles"
    const val ADMINS_FILE = "admins.sqlite"
    const val TIMEZONES_FILE = "timezones.sqlite"

    fun inspect(path: String?): ValhallaPackStatus {
        val directory = path?.let(::File)
        if (directory == null) {
            return notReady(ValhallaPackProblem.Absent, "no pack directory is configured")
        }
        if (!directory.isDirectory) {
            return notReady(ValhallaPackProblem.Absent, "${directory.absolutePath} is not a directory")
        }
        if (directory.list()?.isEmpty() != false) {
            return notReady(ValhallaPackProblem.Absent, "${directory.absolutePath} is empty")
        }
        val config = File(directory, CONFIG_FILE)
        if (!config.isFile) {
            return notReady(ValhallaPackProblem.Incomplete, "${directory.absolutePath} has no $CONFIG_FILE")
        }
        for (name in listOf(ADMINS_FILE, TIMEZONES_FILE)) {
            if (!File(directory, name).isFile) {
                return notReady(ValhallaPackProblem.Incomplete, "${directory.absolutePath} has no $name")
            }
        }
        val tiles =
            tilesIn(directory) ?: return notReady(
                ValhallaPackProblem.Incomplete,
                "${directory.absolutePath} has neither $TILES_ARCHIVE nor a $TILES_DIRECTORY with tile files",
            )
        if (readConfig(config) == null) {
            return notReady(ValhallaPackProblem.Unusable, "${config.absolutePath} is not a readable Valhalla config")
        }
        return ValhallaPackStatus.Ready(directory, tiles)
    }

    private fun tilesIn(directory: File): ValhallaTiles? {
        val archive = File(directory, TILES_ARCHIVE)
        if (archive.isFile) {
            return ValhallaTiles.Extract(archive)
        }
        val tiles = File(directory, TILES_DIRECTORY)
        if (tiles.isDirectory && tiles.walkTopDown().any { it.isFile }) {
            return ValhallaTiles.Directory(tiles)
        }
        return null
    }

    internal fun readConfig(source: File): JsonObject? =
        runCatching { Json.parseToJsonElement(source.readText()) as? JsonObject }.getOrNull()

    private fun notReady(
        problem: ValhallaPackProblem,
        detail: String,
    ): ValhallaPackStatus.NotReady = ValhallaPackStatus.NotReady(problem, detail)
}

/** The engine's one conversion from an inspected pack to a startable one. */
internal fun ValhallaPackStatus.readyOrThrow(): ValhallaPackStatus.Ready =
    when (this) {
        is ValhallaPackStatus.Ready -> this
        is ValhallaPackStatus.NotReady -> throw ValhallaPackException(problem, detail)
    }

/**
 * Writes the local config the native actor reads.
 *
 * valhalla-mobile's `Valhalla(String)` takes a config path and hands it to the
 * native side unchanged, so the app owns every path in it. The pack's own
 * `valhalla.json` is built elsewhere and carries whatever absolute paths that
 * build machine used, so each path the AAR models is replaced with the one on
 * this device; an optional entry the pack does not ship is cleared rather than
 * left pointing at a path that cannot exist here.
 */
internal object ValhallaPackConfig {
    private const val LOCAL_CONFIG_FILE = "valhalla.local.json"
    private const val MAX_CACHE_BYTES = 128 * 1024 * 1024
    private const val NONE = ""
    private const val LANDMARKS_FILE = "landmarks.sqlite"
    private const val TRAFFIC_FILE = "traffic.tar"
    private const val TRANSIT_DIRECTORY = "transit"
    private const val TRANSIT_FEEDS_DIRECTORY = "transit_feeds"
    private const val ELEVATION_DIRECTORY = "elevation"

    /** Writes `valhalla.local.json` into [configDirectory] and returns it. */
    fun write(
        pack: ValhallaPackStatus.Ready,
        configDirectory: File,
    ): File {
        val source = File(pack.directory, ValhallaPack.CONFIG_FILE)
        val root = ValhallaPack.readConfig(source) ?: throw unreadable(source)
        val mjolnir = root["mjolnir"] as? JsonObject ?: throw unreadable(source)
        val patched =
            root +
                ("mjolnir" to JsonObject(mjolnir + devicePaths(pack))) +
                ("additional_data" to additionalData(root, pack.directory))
        if (!configDirectory.isDirectory && !configDirectory.mkdirs()) {
            throw IOException("cannot create ${configDirectory.absolutePath}")
        }
        val target = File(configDirectory, LOCAL_CONFIG_FILE)
        target.writeText(JsonObject(patched).toString())
        return target
    }

    private fun devicePaths(pack: ValhallaPackStatus.Ready): Map<String, JsonElement> {
        val tiles = pack.tiles
        val paths =
            mapOf(
                "tile_extract" to (tiles as? ValhallaTiles.Extract)?.archive,
                "tile_dir" to (tiles as? ValhallaTiles.Directory)?.directory,
                "admin" to File(pack.directory, ValhallaPack.ADMINS_FILE),
                "timezone" to File(pack.directory, ValhallaPack.TIMEZONES_FILE),
                "landmarks" to File(pack.directory, LANDMARKS_FILE),
                "traffic_extract" to File(pack.directory, TRAFFIC_FILE),
                "transit_dir" to File(pack.directory, TRANSIT_DIRECTORY),
                "transit_feeds_dir" to File(pack.directory, TRANSIT_FEEDS_DIRECTORY),
            )
        return paths.mapValues { (_, file) -> JsonPrimitive(file?.takeIf { it.exists() }?.absolutePath ?: NONE) } +
            ("max_cache_size" to JsonPrimitive(MAX_CACHE_BYTES))
    }

    private fun additionalData(
        root: JsonObject,
        pack: File,
    ): JsonObject {
        val existing = root["additional_data"] as? JsonObject ?: JsonObject(emptyMap())
        val elevation = File(pack, ELEVATION_DIRECTORY)
        return JsonObject(
            existing + ("elevation" to JsonPrimitive(if (elevation.exists()) elevation.absolutePath else NONE)),
        )
    }

    private fun unreadable(source: File): ValhallaPackException =
        ValhallaPackException(
            ValhallaPackProblem.Unusable,
            "${source.absolutePath} is not a readable Valhalla config",
        )
}
