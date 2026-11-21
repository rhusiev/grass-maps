package nl.r1a.grassmaps.app.devcheck

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmaps.app.UserDataLayout
import java.io.File

private const val REDACTED = "<redacted>"
private const val UNREADABLE = "unreadable"
private const val ABSENT = "absent"
private val SECRET_KEY = Regex("token|secret|password|authorization|api[-_]?key|^key$", RegexOption.IGNORE_CASE)
private val SECRET_QUERY = Regex("([?&](?:key|token|apikey|access_token)=)[^&\"\\s]+", RegexOption.IGNORE_CASE)

/** Settings files dumped with their values, relative to the app files directory. */
internal val VALUE_FILES =
    listOf(
        // The per-profile stores replaced the app-wide settings/units.json and settings/style.json (T-0603).
        UserDataLayout.UNIT_SETTINGS_PATH,
        "settings/map-labels.json",
        UserDataLayout.MAP_STYLE_SETTINGS_PATH,
        "settings/contours.json",
        "settings/raster.json",
        "settings/online-search.json",
        "settings/auto-update.json",
        "profiles/catalog.json",
        "profiles/routing-options.json",
        "profiles/alerts.json",
    )

/** Files that hold personal history or a token: only the shape leaves the device. */
private val SHAPE_FILES =
    listOf(
        "settings/downloads.json",
        "settings/search-history.json",
        "history/search-history.json",
        "history/route-history.json",
    )

/** Dumps the settings and profile stores for the report, with every secret and personal list removed. */
internal object SettingsDump {
    /** The profile summary plus the settings values, for the profile_store step. */
    fun run(root: File): StepOutcome {
        val base = ProfileStoreSummary.run(root)
        val numbers = base.numbers.toMutableMap()
        numbers["settings"] = dump(root)
        return StepOutcome.pass(numbers)
    }

    fun dump(root: File): JsonObject {
        val entries = mutableMapOf<String, JsonElement>()
        VALUE_FILES.forEach { path -> entries[path] = readValues(File(root, path)) }
        SHAPE_FILES.forEach { path -> entries[path] = readShape(File(root, path)) }
        return JsonObject(entries)
    }

    /** Replaces every secret value in [element], by key name and by query parameter. */
    fun scrub(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> {
                JsonObject(
                    element.mapValues { (key, value) ->
                        if (SECRET_KEY.containsMatchIn(key)) JsonPrimitive(REDACTED) else scrub(value)
                    },
                )
            }

            is JsonArray -> {
                JsonArray(element.map(::scrub))
            }

            is JsonPrimitive -> {
                if (element.isString) JsonPrimitive(SECRET_QUERY.replace(element.content, "$1$REDACTED")) else element
            }
        }

    private fun readValues(file: File): JsonElement {
        if (!file.exists()) {
            return JsonPrimitive(ABSENT)
        }
        val parsed = runCatching { Json.parseToJsonElement(file.readText()) }.getOrNull() ?: return JsonPrimitive(UNREADABLE)
        return scrub(parsed)
    }

    private fun readShape(file: File): JsonElement {
        if (!file.exists()) {
            return JsonPrimitive(ABSENT)
        }
        val parsed = runCatching { Json.parseToJsonElement(file.readText()) }.getOrNull() ?: return JsonPrimitive(UNREADABLE)
        return shape(parsed)
    }

    private fun shape(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> {
                JsonObject(
                    mapOf(
                        "keys" to JsonArray(element.keys.map(::JsonPrimitive)),
                        "entries" to JsonPrimitive(element.size),
                    ),
                )
            }

            is JsonArray -> {
                JsonObject(mapOf("entries" to JsonPrimitive(element.size)))
            }

            is JsonPrimitive -> {
                JsonPrimitive("value")
            }
        }
}
