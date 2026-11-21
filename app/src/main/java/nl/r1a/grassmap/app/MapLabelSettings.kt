package nl.r1a.grassmaps.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import nl.r1a.grassmaps.map.LabelLanguage
import nl.r1a.grassmaps.map.LabelOptions
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import java.io.File
import java.io.IOException

/**
 * The map label language and text size (T-0110). `settings/map-labels.json`
 * holds `{"language":"en","sizePercent":150}`.
 *
 * Construction reads the file, so resolve it off Main. A missing, unreadable or
 * corrupt file, an unknown language and a size outside the allowed range read
 * as the default, which draws the style as authored.
 */
class MapLabelSettings(
    private val file: File? = null,
) {
    private val state = MutableStateFlow(load(file) ?: LabelOptions())

    /** The active options. The map screen passes this flow to the map surface. */
    val options: StateFlow<LabelOptions> = state.asStateFlow()

    /** Stores [next] and returns when the file holds it. */
    @Synchronized
    fun set(next: LabelOptions): Outcome<Unit> {
        if (!isValid(next.sizePercent)) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        state.value = next
        val target = file ?: return Outcome.Success(Unit)
        return try {
            writeAtomically(target, encode(next))
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
        private const val ERROR_ID = "app.map_label_settings"
        const val LANGUAGE_KEY = "language"
        const val SIZE_KEY = "sizePercent"

        /** True when [percent] is a size the map accepts. */
        fun isValid(percent: Int): Boolean = percent in LabelOptions.MIN_SIZE_PERCENT..LabelOptions.MAX_SIZE_PERCENT

        /** The stored form of [options]. */
        fun encode(options: LabelOptions): String =
            """{"$LANGUAGE_KEY":"${options.language.wireName}","$SIZE_KEY":${options.sizePercent}}"""

        private fun load(file: File?): LabelOptions? {
            if (file == null || !file.isFile) {
                return null
            }
            return try {
                val root = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return null
                val language = LabelLanguage.fromWireName((root[LANGUAGE_KEY] as? JsonPrimitive)?.contentOrNull)
                val size = (root[SIZE_KEY] as? JsonPrimitive)?.intOrNull?.takeIf { isValid(it) }
                LabelOptions(
                    language = language ?: LabelLanguage.Default,
                    sizePercent = size ?: LabelOptions.DEFAULT_SIZE_PERCENT,
                )
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
