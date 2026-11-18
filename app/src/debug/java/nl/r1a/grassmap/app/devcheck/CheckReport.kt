package nl.r1a.grassmap.app.devcheck

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How one step of the device check ended. */
internal enum class StepStatus(
    val wire: String,
) {
    PASSED("passed"),
    FAILED("failed"),
    SKIPPED("skipped"),
}

/**
 * One step's record. [numbers] holds the key facts the step measured. It never
 * holds a secret value: the steps put counts, sizes and names there.
 */
internal data class StepResult(
    val id: String,
    val status: StepStatus,
    val durationMs: Long,
    val numbers: Map<String, JsonElement> = emptyMap(),
    val note: String? = null,
)

/** One window screenshot with the metrics of the window it shows. */
internal data class ScreenshotRecord(
    val file: String,
    val screen: String,
    val theme: String,
    val uiNightMode: Boolean,
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val fontScale: Float,
    val source: String,
    val extra: Map<String, JsonElement> = emptyMap(),
) {
    val widthDp: Double get() = widthPx / density.toDouble()
    val heightDp: Double get() = heightPx / density.toDouble()
}

/** A screen the check could not show, with the reason. */
internal data class UnavailableScreen(
    val screen: String,
    val reason: String,
)

/** The whole check, in the order the steps ran. */
internal data class CheckReport(
    val startedAt: String,
    val steps: List<StepResult>,
    val screenshots: List<ScreenshotRecord>,
    val unavailableScreens: List<UnavailableScreen>,
    val notInThisBuild: List<String> = emptyList(),
) {
    val passedCount: Int get() = steps.count { it.status == StepStatus.PASSED }
    val failedCount: Int get() = steps.count { it.status == StepStatus.FAILED }
    val skippedCount: Int get() = steps.count { it.status == StepStatus.SKIPPED }

    /** True when no step failed. A skipped step is not a failure. */
    val isPass: Boolean get() = failedCount == 0

    fun toJson(): String = PRETTY.encodeToString(JsonObject.serializer(), toJsonObject())

    private fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("schema_version", SCHEMA_VERSION)
            put("started_at", startedAt)
            put(
                "summary",
                buildJsonObject {
                    put("result", if (isPass) "pass" else "fail")
                    put("passed", passedCount)
                    put("failed", failedCount)
                    put("skipped", skippedCount)
                },
            )
            put("steps", buildJsonArray { steps.forEach { add(stepJson(it)) } })
            put("screenshots", buildJsonArray { screenshots.forEach { add(screenshotJson(it)) } })
            put(
                "screens_unavailable",
                buildJsonArray {
                    unavailableScreens.forEach {
                        add(
                            buildJsonObject {
                                put("screen", it.screen)
                                put("reason", it.reason)
                            },
                        )
                    }
                },
            )
            put("screens_not_in_this_build", buildJsonArray { notInThisBuild.forEach { add(JsonPrimitive(it)) } })
        }

    private fun stepJson(step: StepResult): JsonObject =
        buildJsonObject {
            put("id", step.id)
            put("status", step.status.wire)
            put("duration_ms", step.durationMs)
            put("note", step.note?.let { JsonPrimitive(it) } ?: JsonNull)
            put("numbers", JsonObject(step.numbers))
        }

    private fun screenshotJson(shot: ScreenshotRecord): JsonObject =
        buildJsonObject {
            put("file", shot.file)
            put("screen", shot.screen)
            put("theme", shot.theme)
            put("ui_night_mode", shot.uiNightMode)
            put("window_px", sizeJson(shot.widthPx, shot.heightPx))
            put("window_dp", sizeJson(roundTenth(shot.widthDp), roundTenth(shot.heightDp)))
            put("density", shot.density)
            put("font_scale", shot.fontScale)
            put("source", shot.source)
            shot.extra.forEach { (key, value) -> put(key, value) }
        }

    private fun sizeJson(
        width: Number,
        height: Number,
    ): JsonObject =
        buildJsonObject {
            put("width", width)
            put("height", height)
        }

    private fun roundTenth(value: Double): Double = Math.round(value * TENTHS) / TENTHS

    companion object {
        const val SCHEMA_VERSION = 1
        private const val TENTHS = 10.0
        private val PRETTY = Json { prettyPrint = true }
    }
}
