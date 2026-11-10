package nl.r1a.grassmap.core.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.File
import java.io.IOException

/**
 * The search history switch (T-0207): on by default, and off stops the history
 * store from writing or reading anything.
 *
 * It is a local setting, not a [nl.r1a.grassmap.plugin.api.service.PrivacySwitches]
 * entry: that registry and its request counters describe contact leaving the
 * device, while this switch gates a local file. The value lives in
 * `settings/search-history.json`, beside the unit setting, until the versioned
 * global settings file exists.
 *
 * The value is read once when the object is constructed, so the feature only
 * asks a boolean. Construction reads the file, so resolve it off the Main
 * thread. A write is atomic; a missing, unreadable or corrupt file reads as
 * [DEFAULT_ENABLED], because a history preference must not stop search from
 * working.
 */
class SearchHistorySettings(
    private val file: File? = null,
    default: Boolean = DEFAULT_ENABLED,
) {
    private val state = MutableStateFlow(load(file) ?: default)

    /** The active switch value. Renderers and settings collect this flow. */
    val enabled: StateFlow<Boolean> = state.asStateFlow()

    /** The value a feature asks for before it writes or reads the store. */
    fun isEnabled(): Boolean = state.value

    /** Stores [enabled] and returns when the file holds it. */
    @Synchronized
    fun set(enabled: Boolean): Outcome<Unit> {
        state.value = enabled
        val target = file ?: return Outcome.Success(Unit)
        return AtomicFiles.write(target, ERROR_ID) { stream ->
            stream.write("""{"enabled":$enabled}""".toByteArray())
        }
    }

    companion object {
        /** History is a local feature and starts on; the switch turns it off. */
        const val DEFAULT_ENABLED: Boolean = true

        private const val ERROR_ID = "core.services.search_history_settings"
        private const val ENABLED_KEY = "enabled"

        private fun load(file: File?): Boolean? {
            if (file == null || !file.isFile) {
                return null
            }
            return try {
                val root = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return null
                (root[ENABLED_KEY] as? JsonPrimitive)?.booleanOrNull
            } catch (exception: IOException) {
                null
            } catch (exception: SerializationException) {
                null
            } catch (exception: SecurityException) {
                null
            }
        }
    }
}
