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
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugins.raster.RasterPresets
import nl.r1a.grassmap.plugins.raster.RasterSource
import nl.r1a.grassmap.plugins.raster.RasterSources
import java.io.File
import java.io.IOException

/**
 * The active online tile source (T-0512). It lives in the host, not in the
 * plugin, because the choice outlives a plugin activation; the contents of
 * `settings/raster.json` are the persisted state.
 *
 * Construction reads the file, so resolve it off Main. A missing, unreadable or
 * corrupt file reads as the OSM preset, which is also the only preset.
 */
class RasterSourceSettings(
    private val file: File? = null,
    default: RasterSource = RasterPresets.openStreetMap,
) {
    private val state = MutableStateFlow(load(file) ?: default)

    /** The source the map should draw, observed by the map screen. */
    val active: StateFlow<RasterSource> = state.asStateFlow()

    fun current(): RasterSource = state.value

    /** Switches back to the OSM preset and stores it. */
    fun useOpenStreetMap(): Outcome<Unit> {
        state.value = RasterPresets.openStreetMap
        return persist(RasterPresets.openStreetMap)
    }

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
        state.value = source
        return when (val saved = persist(source)) {
            is Outcome.Failure -> saved
            is Outcome.Success -> Outcome.Success(source)
        }
    }

    private fun persist(source: RasterSource): Outcome<Unit> {
        val target = file ?: return Outcome.Success(Unit)
        return try {
            writeAtomically(target, encode(source))
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

    private fun encode(source: RasterSource): String =
        buildJsonObject {
            put(SOURCE_KEY, source.id)
            if (!source.isOpenStreetMap()) {
                put(TEMPLATE_KEY, source.tileUrlTemplate)
                put(ATTRIBUTION_KEY, source.attributionHtml)
            }
        }.toString()

    private companion object {
        const val ERROR_ID = "app.raster_settings"
        const val SOURCE_KEY = "source"
        const val TEMPLATE_KEY = "template"
        const val ATTRIBUTION_KEY = "attribution"

        fun load(file: File?): RasterSource? {
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
    }
}
