package nl.r1a.grassmaps.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugins.favourites.layer.FavouriteLayerSettings
import nl.r1a.grassmaps.plugins.favourites.layer.MarkerShape
import java.io.File
import java.io.IOException

/**
 * The four saved-place layer controls (T-0215).
 *
 * They live in the host, not in the plugin, because a layer choice outlives a
 * plugin activation. The contents of `settings/favourites-layer.json` are the
 * persisted state. The constructor reads nothing and [active] holds the
 * shipped defaults until [ensureLoaded] reads the file, so the map screen can
 * build the store while it composes. A missing or corrupt file reads as the
 * shipped defaults.
 */
class FavouritesLayerSettingsStore(
    private val fileProvider: () -> File?,
) {
    constructor(file: File? = null) : this({ file })

    private val state = MutableStateFlow(FavouriteLayerSettings())
    private var isLoaded = false

    /** One value per redraw, so a control change never mixes two settings. */
    val active: StateFlow<FavouriteLayerSettings> = state.asStateFlow()

    fun current(): FavouriteLayerSettings = state.value

    /** Reads the file once; a later call returns at once. It reads the disk, so call it off Main. */
    @Synchronized
    fun ensureLoaded() {
        if (isLoaded) {
            return
        }
        isLoaded = true
        load(fileProvider())?.let { loaded -> state.value = loaded }
    }

    fun setIconsPerPoint(enabled: Boolean): Outcome<Unit> = update { it.copy(iconsPerPoint = enabled) }

    fun setMarkerShape(shape: MarkerShape?): Outcome<Unit> = update { it.copy(markerShape = shape) }

    fun setShowNames(enabled: Boolean): Outcome<Unit> = update { it.copy(showNames = enabled) }

    /** Shows or hides one saved list completely, including its map features. */
    fun setGroupShown(
        group: String,
        shown: Boolean,
    ): Outcome<Unit> =
        update { settings ->
            val hidden = if (shown) settings.hiddenGroups - group else settings.hiddenGroups + group
            settings.copy(hiddenGroups = hidden)
        }

    /** Loads first, so a change made before the first read cannot overwrite the other stored controls. */
    @Synchronized
    private fun update(next: (FavouriteLayerSettings) -> FavouriteLayerSettings): Outcome<Unit> {
        ensureLoaded()
        val updated = next(state.value)
        state.value = updated
        val target = fileProvider() ?: return Outcome.Success(Unit)
        return try {
            writeAtomically(target, encode(updated))
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

    private fun encode(settings: FavouriteLayerSettings): String =
        buildJsonObject {
            put(ICONS_KEY, settings.iconsPerPoint)
            put(SHAPE_KEY, settings.markerShape?.wireName.orEmpty())
            put(NAMES_KEY, settings.showNames)
            put(
                HIDDEN_KEY,
                buildJsonArray {
                    for (group in settings.hiddenGroups.sorted()) add(JsonPrimitive(group))
                },
            )
        }.toString()

    private companion object {
        const val ERROR_ID = "app.favourites_layer_settings"
        const val ICONS_KEY = "icons"
        const val SHAPE_KEY = "shape"
        const val NAMES_KEY = "names"
        const val HIDDEN_KEY = "hidden"

        fun load(file: File?): FavouriteLayerSettings? {
            if (file == null || !file.isFile) return null
            val root =
                try {
                    Json.parseToJsonElement(file.readText()) as? JsonObject
                } catch (exception: IOException) {
                    null
                } catch (exception: SerializationException) {
                    null
                } catch (exception: SecurityException) {
                    null
                } ?: return null
            val hidden =
                (root[HIDDEN_KEY] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?.toSet()
                    ?: emptySet()
            return FavouriteLayerSettings(
                iconsPerPoint = (root[ICONS_KEY] as? JsonPrimitive)?.booleanOrNull ?: true,
                markerShape = MarkerShape.fromWire((root[SHAPE_KEY] as? JsonPrimitive)?.contentOrNull),
                showNames = (root[NAMES_KEY] as? JsonPrimitive)?.booleanOrNull ?: false,
                hiddenGroups = hidden,
            )
        }
    }
}
