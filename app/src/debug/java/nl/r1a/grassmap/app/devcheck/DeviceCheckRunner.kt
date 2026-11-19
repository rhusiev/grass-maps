package nl.r1a.grassmap.app.devcheck

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement

/** What a step reports when it ends. */
internal class StepOutcome(
    val status: StepStatus,
    val numbers: Map<String, JsonElement> = emptyMap(),
    val note: String? = null,
) {
    companion object {
        fun pass(numbers: Map<String, JsonElement> = emptyMap()) = StepOutcome(StepStatus.PASSED, numbers)

        fun fail(
            note: String,
            numbers: Map<String, JsonElement> = emptyMap(),
        ) = StepOutcome(StepStatus.FAILED, numbers, note)

        fun skip(
            note: String,
            numbers: Map<String, JsonElement> = emptyMap(),
        ) = StepOutcome(StepStatus.SKIPPED, numbers, note)
    }
}

internal const val DEFAULT_STEP_TIMEOUT_MS = 120_000L

/** One named step of the check. A step that outlives [timeoutMs] is recorded as failed. */
internal class CheckStep(
    val id: String,
    val timeoutMs: Long = DEFAULT_STEP_TIMEOUT_MS,
    val run: suspend () -> StepOutcome,
)

/**
 * Runs steps in order and times each one. A step that throws is recorded as
 * failed and the next step still runs, so one broken step cannot hide the rest.
 * A cancellation is not a step failure and propagates.
 */
internal class DeviceCheckRunner(
    private val clockMs: () -> Long,
    private val onStepStart: (index: Int, step: CheckStep) -> Unit = { _, _ -> },
    private val onStepEnd: (StepResult) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    suspend fun run(steps: List<CheckStep>): List<StepResult> =
        steps.mapIndexed { index, step ->
            onStepStart(index, step)
            log("step ${step.id}: start")
            runOne(step).also { result ->
                log("step ${result.id}: ${result.status.wire} in ${result.durationMs} ms ${result.note.orEmpty()}".trim())
                onStepEnd(result)
            }
        }

    private suspend fun runOne(step: CheckStep): StepResult {
        val start = clockMs()
        val outcome =
            try {
                withTimeoutOrNull(step.timeoutMs) { step.run() }
                    ?: StepOutcome.fail("timed out after ${step.timeoutMs} ms")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (
                @Suppress("TooGenericExceptionCaught") failure: Exception,
            ) {
                StepOutcome.fail("${failure::class.java.simpleName}: ${failure.message.orEmpty()}")
            }
        return StepResult(step.id, outcome.status, clockMs() - start, outcome.numbers, outcome.note)
    }
}
