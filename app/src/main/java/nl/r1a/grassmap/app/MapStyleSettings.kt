package nl.r1a.grassmap.app

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.StyleId
import nl.r1a.grassmap.plugins.layers.panel.ActiveStyleState
import nl.r1a.grassmap.plugins.layers.panel.MapStyleChoice
import java.io.File
import java.io.IOException

/**
 * The theme the shell and the map share: a capture fixture first, then the
 * debug pin (`core.theme.set`), then the system night mode. The default follows
 * the system; only a debug build can pin it.
 */
internal fun isDarkTheme(
    fixture: Boolean?,
    override: Boolean?,
    systemDark: Boolean,
): Boolean = fixture ?: override ?: systemDark

/**
 * The style variant the map draws for [choice] in [dark]. Route preview, the
 * Stops sheet and every other state share one map screen, so this one function
 * decides the variant for all of them.
 */
internal fun mapStyleId(
    choice: MapStyleChoice,
    dark: Boolean,
): StyleId =
    StyleId(
        when (choice) {
            MapStyleChoice.STANDARD -> if (dark) "standard-dark" else "standard"
            MapStyleChoice.TOURING -> if (dark) "touring-dark" else "touring"
        },
    )

/**
 * The map style the user picked in the layers panel (T-0109). It lives in the
 * host because a style choice is a user preference that outlives a plugin
 * activation. `settings/style.json` holds `{"style":"touring"}`.
 *
 * Construction reads the file, so resolve it off Main. A missing, unreadable or
 * unknown value reads as Meadow, the Standard style.
 */
class MapStyleSettings(
    private val file: File? = null,
) {
    private val state = ActiveStyleState(load(file) ?: DEFAULT_CHOICE)

    /** The active style the map and the layers panel collect. */
    val active: StateFlow<MapStyleChoice> = state.active

    fun current(): MapStyleChoice = state.active.value

    /** Makes [choice] active and returns when the file holds it. */
    @Synchronized
    fun select(choice: MapStyleChoice): Outcome<Unit> {
        state.select(choice)
        val target = file ?: return Outcome.Success(Unit)
        return try {
            writeAtomically(target, """{"$STYLE_KEY":"${choice.styleId}"}""")
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
        /** Meadow is the default style. */
        val DEFAULT_CHOICE: MapStyleChoice = MapStyleChoice.STANDARD

        private const val ERROR_ID = "app.map_style_settings"
        private const val STYLE_KEY = "style"

        private fun load(file: File?): MapStyleChoice? {
            if (file == null || !file.isFile) {
                return null
            }
            return try {
                val root = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return null
                MapStyleChoice.fromStyleId((root[STYLE_KEY] as? JsonPrimitive)?.contentOrNull)
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
