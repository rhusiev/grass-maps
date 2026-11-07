package nl.r1a.grassmap.core.services

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.File
import java.io.IOException

/**
 * The user's diagnostic-logging toggle.
 *
 * The value is read once when the settings object is constructed, so the
 * logging path only reads a volatile Boolean and never touches the disk. The
 * state file is written atomically. A missing, unreadable or corrupt file reads
 * as [defaultEnabled], never as an error: diagnostics must not be the reason
 * the app fails to start.
 *
 * Construction resolves and reads the file, so it must happen off the Main
 * thread (the composition root constructs it on its first diagnostic access,
 * which is an IO-dispatched command or the crash handler).
 */
class DiagnosticSettings(
    private val file: File? = null,
    defaultEnabled: Boolean = false,
) {
    @Volatile
    private var enabled: Boolean = load(file) ?: defaultEnabled

    fun isEnabled(): Boolean = enabled

    @Synchronized
    fun setEnabled(value: Boolean): Outcome<Unit> {
        enabled = value
        val target = file ?: return Outcome.Success(Unit)
        return AtomicFiles.write(target, ERROR_ID) { stream ->
            stream.write("""{"logging":$value}""".toByteArray())
        }
    }

    private companion object {
        const val ERROR_ID = "core.services.diagnostic_settings"

        fun load(file: File?): Boolean? {
            if (file == null || !file.isFile) {
                return null
            }
            return try {
                val root = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return null
                (root[LOGGING_KEY] as? JsonPrimitive)?.booleanOrNull
            } catch (exception: IOException) {
                null
            } catch (exception: SerializationException) {
                null
            } catch (exception: SecurityException) {
                null
            }
        }

        const val LOGGING_KEY = "logging"
    }
}
