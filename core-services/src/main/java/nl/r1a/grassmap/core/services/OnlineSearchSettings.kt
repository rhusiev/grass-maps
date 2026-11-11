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
 * The separate "send my position" allowance for online search (T-0212). It is
 * independent of the `network.online_search` privacy switch: the switch permits
 * the request, and this setting decides whether the request carries the map
 * position. Both start off.
 *
 * The value lives in `settings/online-search.json`. Construction reads the
 * file, so resolve it off the Main thread. A missing, unreadable or corrupt
 * file reads as off, because a position allowance must never turn itself on.
 */
class OnlineSearchSettings(
    private val file: File? = null,
    defaultPositionAllowed: Boolean = DEFAULT_POSITION_ALLOWED,
) {
    private val state = MutableStateFlow(load(file) ?: defaultPositionAllowed)

    /** The active allowance; the screen collects this and the merge reads it. */
    val positionAllowed: StateFlow<Boolean> = state.asStateFlow()

    /** The value the search path asks before it adds lat/lon to a query. */
    fun isPositionAllowed(): Boolean = state.value

    /** Stores [allowed] and returns when the file holds it. */
    @Synchronized
    fun setPositionAllowed(allowed: Boolean): Outcome<Unit> {
        state.value = allowed
        val target = file ?: return Outcome.Success(Unit)
        return AtomicFiles.write(target, ERROR_ID) { stream ->
            stream.write("""{"positionAllowed":$allowed}""".toByteArray())
        }
    }

    companion object {
        /** Sending the map position is an extra choice, so it starts off. */
        const val DEFAULT_POSITION_ALLOWED: Boolean = false

        private const val ERROR_ID = "core.services.online_search_settings"
        private const val POSITION_ALLOWED_KEY = "positionAllowed"

        private fun load(file: File?): Boolean? {
            if (file == null || !file.isFile) {
                return null
            }
            return try {
                val root = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return null
                (root[POSITION_ALLOWED_KEY] as? JsonPrimitive)?.booleanOrNull
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
