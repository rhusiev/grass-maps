package nl.r1a.grassmap.app.devcheck

import kotlinx.coroutines.CancellationException
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

/** One named step of the check. */
internal class CheckStep(
    val id: String,
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
            runOne(step).also { result ->
                log("step ${result.id}: ${result.status.wire} in ${result.durationMs} ms ${result.note.orEmpty()}".trim())
                onStepEnd(result)
            }
        }

    private suspend fun runOne(step: CheckStep): StepResult {
        val start = clockMs()
        val outcome =
            try {
                step.run()
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
