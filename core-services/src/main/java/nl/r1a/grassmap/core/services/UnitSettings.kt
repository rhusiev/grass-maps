package nl.r1a.grassmap.core.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.UnitSystem
import java.io.File
import java.io.IOException

/**
 * The user's length unit system (T-0107), metric by default.
 *
 * The value is read once when the object is constructed, so a renderer only
 * reads a flow and never touches the disk. A write is atomic; a missing,
 * unreadable or corrupt file reads as metric, never as an error, because a unit
 * preference must not stop the map from starting. Construction reads the file,
 * so resolve it off the Main thread.
 */
class UnitSettings(
    private val file: File? = null,
    default: UnitSystem = UnitSystem.Metric,
) {
    private val state = MutableStateFlow(load(file) ?: default)

    /** The active unit system. Renderers collect this flow. */
    val units: StateFlow<UnitSystem> = state.asStateFlow()

    /** Stores [units] and returns when the file holds it. */
    @Synchronized
    fun set(units: UnitSystem): Outcome<Unit> {
        state.value = units
        val target = file ?: return Outcome.Success(Unit)
        return AtomicFiles.write(target, ERROR_ID) { stream ->
            stream.write("""{"units":"${wireName(units)}"}""".toByteArray())
        }
    }

    private companion object {
        const val ERROR_ID = "core.services.unit_settings"
        const val UNITS_KEY = "units"

        fun wireName(units: UnitSystem): String =
            when (units) {
                UnitSystem.Metric -> "metric"
                UnitSystem.Imperial -> "imperial"
            }

        fun load(file: File?): UnitSystem? {
            if (file == null || !file.isFile) {
                return null
            }
            return try {
                val root = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return null
                when ((root[UNITS_KEY] as? JsonPrimitive)?.contentOrNull) {
                    "metric" -> UnitSystem.Metric
                    "imperial" -> UnitSystem.Imperial
                    else -> null
                }
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
