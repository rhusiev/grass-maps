package nl.r1a.grassmap.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugins.raster.RasterPlacement
import nl.r1a.grassmap.plugins.raster.RasterPresets
import nl.r1a.grassmap.plugins.raster.RasterSource
import nl.r1a.grassmap.plugins.raster.RasterSources
import nl.r1a.grassmap.plugins.raster.RasterTransparency
import java.io.File
import java.io.IOException

/**
 * The online raster layer itself (T-0512): the active tile source, where it
 * draws and how transparent it is (T-0514). It lives in the host, not in the
 * plugin, because the choice outlives a plugin activation; the contents of
 * `settings/raster.json` are the persisted state.
 *
 * Construction reads the file, so resolve it off Main. A missing, unreadable or
 * corrupt file reads as the OSM preset over the base map at full opacity, which
 * is also the only preset. A file from before T-0514 lacks the placement and
 * transparency keys and reads with their defaults.
 */
class RasterLayerSettings(
    private val file: File? = null,
    default: RasterSource = RasterPresets.openStreetMap,
) {
    private val state = MutableStateFlow(load(file) ?: initial(default))

    /** The layer the map screen draws, observed as one value so a redraw never mixes two settings. */
    val active: StateFlow<RasterLayerState> = state.asStateFlow()

    fun current(): RasterLayerState = state.value

    /** Switches back to the OSM preset and stores it. */
    fun useOpenStreetMap(): Outcome<Unit> = update { it.copy(source = RasterPresets.openStreetMap) }

    /**
     * Validates [template], asks [probe] to fetch one tile through the network
     * gate, and only then makes the source active and stores it. A template
     * that cannot serve one tile never replaces a working source.
     */
    suspend fun useCustom(
        template: String,
        probe: suspend (RasterSource) -> Outcome<Unit>,
    ): Outcome<RasterSource> {
        val source =
            when (val parsed = RasterSources.custom(template.trim())) {
                is Outcome.Failure -> return parsed
                is Outcome.Success -> parsed.value
            }
        when (val reachable = probe(source)) {
            is Outcome.Failure -> return reachable
            is Outcome.Success -> Unit
        }
        return when (val saved = update { it.copy(source = source) }) {
            is Outcome.Failure -> saved
            is Outcome.Success -> Outcome.Success(source)
        }
    }

    fun setPlacement(placement: RasterPlacement): Outcome<Unit> = update { it.copy(placement = placement) }

    /**
     * Sets the transparency. [store] writes the settings file: a drag updates
     * the map on every step and stores the value the finger leaves, so a drag
     * is one write and not one per frame.
     */
    fun setTransparencyPercent(
        percent: Int,
        store: Boolean = true,
    ): Outcome<Unit> =
        update(store) {
            it.copy(transparencyPercent = percent.coerceIn(RasterTransparency.MIN_PERCENT, RasterTransparency.MAX_PERCENT))
        }

    private fun update(
        store: Boolean = true,
        next: (RasterLayerState) -> RasterLayerState,
    ): Outcome<Unit> {
        val updated = next(state.value)
        state.value = updated
        return if (store) persist(updated) else Outcome.Success(Unit)
    }

    private fun persist(layer: RasterLayerState): Outcome<Unit> {
        val target = file ?: return Outcome.Success(Unit)
        return try {
            writeAtomically(target, encode(layer))
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

    private fun encode(layer: RasterLayerState): String =
        buildJsonObject {
            put(SOURCE_KEY, layer.source.id)
            if (!layer.source.isOpenStreetMap()) {
                put(TEMPLATE_KEY, layer.source.tileUrlTemplate)
                put(ATTRIBUTION_KEY, layer.source.attributionHtml)
            }
            put(PLACEMENT_KEY, layer.placement.name.lowercase())
            put(TRANSPARENCY_KEY, layer.transparencyPercent)
        }.toString()

    private companion object {
        const val ERROR_ID = "app.raster_settings"
        const val SOURCE_KEY = "source"
        const val TEMPLATE_KEY = "template"
        const val ATTRIBUTION_KEY = "attribution"
        const val PLACEMENT_KEY = "placement"
        const val TRANSPARENCY_KEY = "transparency"
        const val DEFAULT_TRANSPARENCY_PERCENT = RasterTransparency.MAX_PERCENT

        fun initial(source: RasterSource): RasterLayerState =
            RasterLayerState(
                source = source,
                placement = RasterPlacement.Overlay,
                transparencyPercent = DEFAULT_TRANSPARENCY_PERCENT,
            )

        fun load(file: File?): RasterLayerState? {
            if (file == null || !file.isFile) {
                return null
            }
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
            val source = loadSource(root) ?: return null
            return initial(source).copy(
                placement = placementOf((root[PLACEMENT_KEY] as? JsonPrimitive)?.contentOrNull),
                transparencyPercent =
                    (root[TRANSPARENCY_KEY] as? JsonPrimitive)
                        ?.intOrNull
                        ?.coerceIn(RasterTransparency.MIN_PERCENT, RasterTransparency.MAX_PERCENT)
                        ?: DEFAULT_TRANSPARENCY_PERCENT,
            )
        }

        fun loadSource(root: JsonObject): RasterSource? {
            val id = (root[SOURCE_KEY] as? JsonPrimitive)?.contentOrNull
            if (id == RasterPresets.OPEN_STREET_MAP_ID) {
                return RasterPresets.openStreetMap
            }
            if (id != RasterSources.CUSTOM_ID) {
                return null
            }
            val template = (root[TEMPLATE_KEY] as? JsonPrimitive)?.contentOrNull ?: return null
            val attribution = (root[ATTRIBUTION_KEY] as? JsonPrimitive)?.contentOrNull.orEmpty()
            return when (val parsed = RasterSources.custom(template, attribution)) {
                is Outcome.Success -> parsed.value
                is Outcome.Failure -> null
            }
        }

        fun placementOf(text: String?): RasterPlacement =
            RasterPlacement.entries.firstOrNull { it.name.equals(text, ignoreCase = true) }
                ?: RasterPlacement.Overlay
    }
}

/** One raster layer: the source it draws, where, and how transparent it is. */
data class RasterLayerState(
    val source: RasterSource,
    val placement: RasterPlacement,
    /** The transparency the control shows, as whole percent of `raster-opacity`. */
    val transparencyPercent: Int,
)
