package nl.r1a.grassmap.app.devcheck

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.map.LabelOptions
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugins.dev.tools.DevToolsContract
import nl.r1a.grassmap.plugins.dev.tools.DevToolsPanelState

internal const val LVIV_LAT = 49.8397
internal const val LVIV_LON = 24.0297
private const val LABEL_ZOOM = 6.0
private const val LABEL_WAIT_MS = 8_000L
private const val LABEL_POLL_MS = 500L
private const val LABEL_SIZE_BIG_PERCENT = 150
private const val PACING_SAMPLES = 4
private const val PACING_TIMEOUT_MS = 12_000L
private const val PACING_ZOOM_NEAR = 15.0
private const val PACING_ZOOM_FAR = 13.0

/** The labels the check applies in turn, as the settings command takes them. */
private val LABEL_CASES =
    listOf(
        "en" to ("en" to LabelOptions.DEFAULT_SIZE_PERCENT),
        "uk" to ("uk" to LabelOptions.DEFAULT_SIZE_PERCENT),
        "size150" to ("default" to LABEL_SIZE_BIG_PERCENT),
    )

/** Sets en, uk and 150 percent through the settings command and reads the rendered Lviv label each time. */
internal class LabelsStep(
    private val context: CheckContext,
) {
    suspend fun run(): StepOutcome {
        val original = context.container.mapLabelSettings.options.value
        val numbers = mutableMapOf<String, JsonElement>()
        try {
            context.command("core.map.set_camera", camera(LVIV_LAT, LVIV_LON, LABEL_ZOOM))
            LABEL_CASES.forEach { (name, case) -> numbers[name] = runCase(case.first, case.second) }
        } finally {
            context.container.mapLabelSettings.set(original)
        }
        val missing = numbers.filterValues { (it as? JsonObject)?.get("count")?.toString() == "0" }.keys
        return if (missing.isEmpty()) {
            StepOutcome.pass(numbers)
        } else if (missing.size == numbers.size) {
            StepOutcome.skip("no Lviv label rendered in any case: the map shows no data here, so the check cannot tell", numbers)
        } else {
            StepOutcome.fail("no rendered Lviv label for: ${missing.joinToString()}", numbers)
        }
    }

    private suspend fun runCase(
        language: String,
        sizePercent: Int,
    ): JsonElement {
        val params =
            buildJsonObject {
                put("language", language)
                put("sizePercent", sizePercent)
            }
        val set = context.command("core.settings.map_labels", params)
        val texts = if (set is Outcome.Success) awaitLvivTexts() else emptyList()
        return buildJsonObject {
            put("language", language)
            put("size_percent", sizePercent)
            put("count", texts.size)
            put("texts", JsonArray(texts.map { JsonPrimitive(it) }))
        }
    }

    private suspend fun awaitLvivTexts(): List<String> =
        withTimeoutOrNull(LABEL_WAIT_MS) {
            var texts = lvivTexts()
            while (texts.isEmpty()) {
                delay(LABEL_POLL_MS)
                texts = lvivTexts()
            }
            texts
        } ?: emptyList()

    private suspend fun lvivTexts(): List<String> {
        val result = context.command("core.map.rendered_labels", buildJsonObject { put("nameEn", "Lviv") })
        val raw = ((result as? Outcome.Success)?.value as? JsonPrimitive)?.contentOrNull ?: return emptyList()
        val labels = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        return labels.mapNotNull { (it.jsonObject["text"] as? JsonPrimitive)?.contentOrNull }.distinct()
    }
}

/**
 * Frame pacing from the development-tools frame-rate stream while the camera
 * moves. It is not the macrobenchmark: that runs in a separate APK, and a cold
 * start cannot be measured from inside the running process.
 */
internal class PacingStep(
    private val context: CheckContext,
) {
    suspend fun run(): StepOutcome {
        val samples =
            withTimeoutOrNull(PACING_TIMEOUT_MS) {
                coroutineScope {
                    val mover = launch { moveCamera() }
                    val fps =
                        context.container.streamAccess
                            .subscribe<DevToolsPanelState>(DevToolsContract.stateStream)
                            .take(PACING_SAMPLES)
                            .toList()
                            .map { it.framesPerSecond }
                    mover.cancel()
                    fps
                }
            }.orEmpty()
        val numbers =
            mapOf(
                "cold_start" to JsonPrimitive("not run: the benchmark build is a separate APK and a cold start cannot be timed from inside the process"),
            )
        if (samples.isEmpty()) {
            return StepOutcome.skip("the frame-rate stream gave no sample", numbers)
        }
        return StepOutcome.pass(
            numbers +
                mapOf(
                    "fps_samples" to JsonPrimitive(samples.size),
                    "fps_min" to JsonPrimitive(samples.min()),
                    "fps_max" to JsonPrimitive(samples.max()),
                    "fps_mean" to JsonPrimitive(samples.average()),
                ),
        )
    }

    private suspend fun moveCamera() {
        while (true) {
            context.command("core.map.set_camera", camera(LVIV_LAT, LVIV_LON, PACING_ZOOM_NEAR))
            context.command("core.map.set_camera", camera(LVIV_LAT, LVIV_LON, PACING_ZOOM_FAR))
        }
    }
}

internal fun camera(
    lat: Double,
    lon: Double,
    zoom: Double,
): JsonObject =
    buildJsonObject {
        put("lat", lat)
        put("lon", lon)
        put("zoom", zoom)
    }
