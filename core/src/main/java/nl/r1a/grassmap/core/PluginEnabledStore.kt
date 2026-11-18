package nl.r1a.grassmap.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nl.r1a.grassmap.plugin.api.contract.PluginId
import java.io.File
import java.io.IOException

/**
 * Persists which plugins the user turned off (T-0607). Both calls do file I/O,
 * so the host calls them off Main.
 */
interface PluginEnabledStore {
    /** The plugins the user turned off. A missing or unreadable store is empty. */
    fun loadDisabled(): Set<PluginId>

    /** Stores [disabled] and returns when the store holds it. Throws [IOException]. */
    fun saveDisabled(disabled: Set<PluginId>)
}

/** The store before the app wires a file: nothing is off and nothing persists. */
object NoPluginEnabledStore : PluginEnabledStore {
    override fun loadDisabled(): Set<PluginId> = emptySet()

    override fun saveDisabled(disabled: Set<PluginId>) = Unit
}

/**
 * Keeps `{"version":1,"disabled":["plugin.id"]}` in [file]. A missing, corrupt
 * or newer-version file reads as "all on" and is overwritten by the next save.
 * The write goes through a `.part` file and a rename, so a kill mid-write
 * leaves the previous file whole.
 */
class FilePluginEnabledStore(
    private val file: File,
) : PluginEnabledStore {
    override fun loadDisabled(): Set<PluginId> {
        if (!file.isFile) {
            return emptySet()
        }
        return try {
            val root = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return emptySet()
            val version = (root[VERSION_KEY] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            val disabled = root[DISABLED_KEY] as? JsonArray
            if (version != SCHEMA_VERSION || disabled == null) {
                emptySet()
            } else {
                disabled.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.map(::PluginId).toSet()
            }
        } catch (exception: IOException) {
            emptySet()
        } catch (exception: SerializationException) {
            emptySet()
        } catch (exception: SecurityException) {
            emptySet()
        }
    }

    @Synchronized
    override fun saveDisabled(disabled: Set<PluginId>) {
        val document =
            JsonObject(
                mapOf(
                    VERSION_KEY to JsonPrimitive(SCHEMA_VERSION),
                    DISABLED_KEY to JsonArray(disabled.map { JsonPrimitive(it.value) }.sortedBy { it.content }),
                ),
            )
        file.parentFile?.mkdirs()
        val partial = File(file.parentFile, "${file.name}.part")
        partial.writeText(document.toString())
        if (!partial.renameTo(file)) {
            partial.copyTo(file, overwrite = true)
            partial.delete()
        }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val VERSION_KEY = "version"
        const val DISABLED_KEY = "disabled"
    }
}
