package nl.r1a.grassmap.core.services

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import nl.r1a.grassmap.core.PluginEnabledStore
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.File
import java.io.IOException

/**
 * Keeps `{"version":1,"disabled":["plugin.id"]}` in [file].
 *
 * The rules are [ProfileValueFile]'s: only a missing file reads as empty, a
 * file this build cannot decode fails with [AppError.CorruptData], and one a
 * newer build wrote fails with [AppError.NewerSchema] on read and on write. A
 * refused file is never replaced, so an older build cannot drop choices it does
 * not understand. The write is [AtomicFiles]'s: a temporary sibling, an fsync,
 * an atomic move, and the temporary removed when any step fails. Only the ids
 * of plugins that exist are stored; the host filters the unknown ones out.
 */
class FilePluginEnabledStore(
    private val file: File,
    private val errorId: String = ERROR_ID,
) : PluginEnabledStore {
    override fun loadDisabled(): Outcome<Set<PluginId>> {
        if (!file.isFile) {
            return Outcome.Success(emptySet())
        }
        return try {
            decode(file.readText())
        } catch (exception: IOException) {
            Outcome.Failure(AppError.Internal(errorId, exception.javaClass.simpleName))
        } catch (exception: SecurityException) {
            Outcome.Failure(AppError.Internal(errorId, exception.javaClass.simpleName))
        }
    }

    @Synchronized
    override fun saveDisabled(disabled: Set<PluginId>): Outcome<Unit> =
        when (val current = loadDisabled()) {
            is Outcome.Failure -> current
            is Outcome.Success -> AtomicFiles.write(file, errorId) { stream -> stream.write(encode(disabled).toByteArray()) }
        }

    private fun decode(text: String): Outcome<Set<PluginId>> {
        val root =
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (exception: SerializationException) {
                null
            } ?: return Outcome.Failure(AppError.CorruptData)
        val version = (root[VERSION_KEY] as? JsonPrimitive)?.intOrNull ?: return Outcome.Failure(AppError.CorruptData)
        if (version > SCHEMA_VERSION) {
            return Outcome.Failure(AppError.NewerSchema(found = version, supported = SCHEMA_VERSION))
        }
        val disabled = root[DISABLED_KEY] as? JsonArray ?: return Outcome.Failure(AppError.CorruptData)
        return Outcome.Success(
            disabled.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.map(::PluginId).toSet(),
        )
    }

    private fun encode(disabled: Set<PluginId>): String =
        buildJsonObject {
            put(VERSION_KEY, SCHEMA_VERSION)
            put(DISABLED_KEY, JsonArray(disabled.map { JsonPrimitive(it.value) }.sortedBy { it.content }))
        }.toString()

    companion object {
        const val SCHEMA_VERSION: Int = 1

        /** The error id every refusal carries, so the caller can name the store. */
        const val ERROR_ID: String = "core.plugin_enabled_store"

        private const val VERSION_KEY = "version"
        private const val DISABLED_KEY = "disabled"
    }
}
