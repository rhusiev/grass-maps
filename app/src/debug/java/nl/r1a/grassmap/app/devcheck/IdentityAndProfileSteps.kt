package nl.r1a.grassmap.app.devcheck

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import nl.r1a.grassmap.app.BuildConfig
import java.io.File

/** The device-check steps that only read state: build identity and the profile files. */
internal object IdentityStep {
    private val PERMISSIONS =
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )

    fun permissionNames(): List<String> = PERMISSIONS

    /** Never asks: a missing permission is recorded and the later steps carry on without it. */
    fun run(context: Context): StepOutcome =
        StepOutcome.pass(
            mapOf(
                "permissions" to permissionStates(context),
                "commit" to JsonPrimitive(BuildConfig.BUILD_COMMIT),
                "commit_time" to JsonPrimitive(BuildConfig.BUILD_COMMIT_TIME),
                "tree_dirty" to JsonPrimitive(BuildConfig.BUILD_TREE_DIRTY),
                "version_name" to JsonPrimitive(BuildConfig.VERSION_NAME),
                "device_manufacturer" to JsonPrimitive(Build.MANUFACTURER),
                "device_model" to JsonPrimitive(Build.MODEL),
                "android_release" to JsonPrimitive(Build.VERSION.RELEASE),
                "android_sdk" to JsonPrimitive(Build.VERSION.SDK_INT),
            ),
        )
}

private fun permissionStates(context: Context): JsonObject =
    JsonObject(
        IdentityStep.permissionNames().associate { name ->
            val granted = context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
            name.substringAfterLast('.') to JsonPrimitive(if (granted) "granted" else "denied")
        },
    )

/** What a profile file holds, with the keys counted and the values never read out. */
internal data class ProfileFileSummary(
    val schemaVersion: Int?,
    val keysPerProfile: Map<String, Int>,
)

/** Reads the profile store files for the report. Only names and counts leave this object. */
internal object ProfileStoreSummary {
    const val PROFILES_KEY = "profiles"
    const val SCHEMA_KEY = "schema_version"
    private const val UNREADABLE = "unreadable"
    private val FILES = listOf("catalog", "routing-options", "alerts")

    /** Counts the profiles and the keys of each one in [text], or null when it is not a profile file. */
    fun summarize(text: String): ProfileFileSummary? {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val profiles = root[PROFILES_KEY] as? JsonObject ?: return null
        val counts = profiles.mapValues { (_, value) -> (value as? JsonObject)?.size ?: 0 }
        return ProfileFileSummary((root[SCHEMA_KEY] as? JsonPrimitive)?.intOrNull, counts)
    }

    /** The step result for the profile files under [root] and their legacy sources. */
    fun run(root: File): StepOutcome {
        val numbers = mutableMapOf<String, JsonElement>()
        FILES.forEach { name -> numbers[name] = describe(File(root, "profiles/$name.json")) }
        numbers["legacy_routing_options"] = JsonPrimitive(legacyState(File(root, "settings/routing-options.json")))
        numbers["legacy_alerts"] = JsonPrimitive(legacyState(File(root, "settings/alerts.json")))
        return StepOutcome.pass(numbers)
    }

    private fun legacyState(file: File): String = if (file.exists()) "present" else "retired"

    private fun describe(file: File): JsonElement {
        if (!file.exists()) {
            return JsonPrimitive("absent")
        }
        val summary = runCatching { summarize(file.readText()) }.getOrNull() ?: return JsonPrimitive(UNREADABLE)
        return JsonObject(
            mapOf(
                "schema_version" to JsonPrimitive(summary.schemaVersion),
                "profiles" to JsonPrimitive(summary.keysPerProfile.size),
                "keys_per_profile" to JsonObject(summary.keysPerProfile.mapValues { JsonPrimitive(it.value) }),
            ),
        )
    }
}
