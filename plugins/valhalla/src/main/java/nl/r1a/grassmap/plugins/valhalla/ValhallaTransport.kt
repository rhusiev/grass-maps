package nl.r1a.grassmap.plugins.valhalla

import com.valhalla.valhalla.Valhalla
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.Closeable
import java.io.File
import java.io.IOException

/** One way to run a Valhalla request body. Tests replace the native engine. */
internal fun interface ValhallaTransport : Closeable {
    fun route(requestJson: String): String

    /**
     * Runs a `trace_attributes` body. Valhalla's library exposes it separately
     * from `route`, so an engine that only needs matching leaves this unused.
     */
    fun traceAttributes(requestJson: String): String = throw NotImplementedError("trace_attributes is not available")

    override fun close() = Unit
}

/** The device pack is incomplete; no engine can start. */
internal class ValhallaPackMissingException : Exception("valhalla pack is incomplete")

/** The device pack has a config the app cannot read. */
internal class ValhallaPackCorruptException : Exception("valhalla config is unreadable")

/**
 * The native valhalla-mobile engine. It writes a config whose paths point at
 * the copied pack, then builds the actor from that file. The actor is created
 * on the first route and reused for the process lifetime.
 */
internal class NativeValhallaTransport(
    private val packDirectory: File,
    private val configDirectory: File,
) : ValhallaTransport {
    private val engineDelegate = lazy { createEngine() }
    private val engine: Valhalla by engineDelegate

    override fun route(requestJson: String): String = engine.routeRaw(requestJson)

    override fun traceAttributes(requestJson: String): String = engine.traceAttributesRaw(requestJson)

    override fun close() {
        if (engineDelegate.isInitialized()) {
            engine.close()
        }
    }

    private fun createEngine(): Valhalla {
        val config = writeConfig()
        return Valhalla(config.absolutePath)
    }

    private fun writeConfig(): File {
        val source = File(packDirectory, CONFIG_FILE)
        if (!source.isFile) {
            throw ValhallaPackMissingException()
        }
        for (name in REQUIRED_FILES) {
            if (!File(packDirectory, name).isFile) {
                throw ValhallaPackMissingException()
            }
        }
        val root =
            runCatching { Json.parseToJsonElement(source.readText()) as? JsonObject }
                .getOrNull()
                ?: throw ValhallaPackCorruptException()
        val mjolnir = root["mjolnir"] as? JsonObject ?: throw ValhallaPackCorruptException()
        val patchedMjolnir =
            JsonObject(
                mjolnir +
                    mapOf(
                        "tile_extract" to JsonPrimitive(File(packDirectory, TILES_FILE).absolutePath),
                        "tile_dir" to JsonPrimitive(File(packDirectory, TILES_DIRECTORY).absolutePath),
                        "admin" to JsonPrimitive(File(packDirectory, ADMINS_FILE).absolutePath),
                        "timezone" to JsonPrimitive(File(packDirectory, TIMEZONES_FILE).absolutePath),
                        "max_cache_size" to JsonPrimitive(MAX_CACHE_BYTES),
                    ),
            )
        val patched = JsonObject(root + ("mjolnir" to patchedMjolnir))
        if (!configDirectory.isDirectory && !configDirectory.mkdirs()) {
            throw IOException("cannot create ${configDirectory.absolutePath}")
        }
        val target = File(configDirectory, LOCAL_CONFIG_FILE)
        target.writeText(patched.toString())
        return target
    }

    private companion object {
        const val CONFIG_FILE = "valhalla.json"
        const val LOCAL_CONFIG_FILE = "valhalla.local.json"
        const val TILES_FILE = "tiles.tar"
        const val TILES_DIRECTORY = "tiles"
        const val ADMINS_FILE = "admins.sqlite"
        const val TIMEZONES_FILE = "timezones.sqlite"
        val REQUIRED_FILES = listOf(TILES_FILE, ADMINS_FILE, TIMEZONES_FILE)
        const val MAX_CACHE_BYTES = 128 * 1024 * 1024
    }
}

/** The pack keys a debug command can use to name the directory it found. */
internal object ValhallaPackFiles {
    const val TILES_FILE = "tiles.tar"
    const val CONFIG_FILE = "valhalla.json"
    const val ADMINS_FILE = "admins.sqlite"
    const val TIMEZONES_FILE = "timezones.sqlite"

    val REQUIRED = listOf(TILES_FILE, CONFIG_FILE, ADMINS_FILE, TIMEZONES_FILE)

    fun isComplete(directory: File): Boolean = REQUIRED.all { File(directory, it).isFile }
}
