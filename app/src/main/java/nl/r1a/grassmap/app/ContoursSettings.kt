package nl.r1a.grassmap.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.File
import java.io.IOException

/**
 * The "Contours" layer switch (T-0417). It lives in the host, not in the
 * plugin, because a layer toggle is a user preference that outlives a plugin
 * activation; the contents of `settings/contours.json` are the persisted state.
 *
 * Construction reads the file unless the golden harness supplied a launch
 * override, so resolve it off Main. A missing, unreadable or corrupt file reads
 * as the default, which is on: the region ships the contour component as part
 * of the map, and the layers panel turns it off.
 */
class ContoursSettings(
    private val file: File? = null,
    defaultEnabled: Boolean = DEFAULT_ENABLED,
    override: () -> Boolean? = { null },
) {
    private val state = MutableStateFlow(override() ?: load(file) ?: defaultEnabled)

    /** The active state the map and the layers panel collect. */
    val enabled: StateFlow<Boolean> = state.asStateFlow()

    fun isEnabled(): Boolean = state.value

    /** Stores [enabled] and returns when the file holds it. */
    @Synchronized
    fun setEnabled(enabled: Boolean): Outcome<Unit> {
        state.value = enabled
        val target = file ?: return Outcome.Success(Unit)
        return try {
            writeAtomically(target, """{"enabled":$enabled}""")
            Outcome.Success(Unit)
        } catch (exception: IOException) {
            Outcome.Failure(AppError.Internal(ERROR_ID, exception.message))
        } catch (exception: SecurityException) {
            Outcome.Failure(AppError.Internal(ERROR_ID, exception.message))
        }
    }

    private fun writeAtomically(
        target: File,
        text: String,
    ) {
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, "${target.name}.part")
        partial.writeText(text)
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
    }

    companion object {
        /** Contours are part of the map by default; the panel turns them off. */
        const val DEFAULT_ENABLED: Boolean = true

        /** The debug launch extra the golden harness passes. */
        const val EXTRA_ENABLED: String = "map.contours.enabled"

        private const val ERROR_ID = "app.contours_settings"
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
