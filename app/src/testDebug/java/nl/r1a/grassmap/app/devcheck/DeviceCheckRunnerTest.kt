package nl.r1a.grassmap.app.devcheck

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCheckRunnerTest {
    private var now = 0L
    private val runner = DeviceCheckRunner(clockMs = { now })

    @Test
    fun aThrowingStepFailsAndTheNextStepStillRuns() {
        val steps =
            listOf(
                CheckStep("boom") { error("bad state") },
                CheckStep("fine") {
                    now += 40
                    StepOutcome.pass()
                },
            )

        val results = runBlocking { runner.run(steps) }

        assertEquals(listOf(StepStatus.FAILED, StepStatus.PASSED), results.map { it.status })
        assertTrue(results[0].note!!.contains("bad state"))
        assertEquals(40L, results[1].durationMs)
    }

    @Test
    fun cancellationIsNotAStepFailure() {
        val steps = listOf(CheckStep("cancelled") { throw CancellationException("stop") })
        assertThrows(CancellationException::class.java) { runBlocking { runner.run(steps) } }
    }

    @Test
    fun stepsRunInTheGivenOrder() {
        val order = mutableListOf<String>()
        val steps =
            listOf("a", "b", "c").map { id ->
                CheckStep(id) {
                    order += id
                    StepOutcome.skip("none")
                }
            }

        runBlocking { runner.run(steps) }

        assertEquals(listOf("a", "b", "c"), order)
    }

    @Test
    fun aStepThatOutlivesItsTimeoutFailsAndTheNextStepRuns() {
        val steps =
            listOf(
                CheckStep("slow", timeoutMs = 20) {
                    delay(SLOW_STEP_MS)
                    StepOutcome.pass()
                },
                CheckStep("after") { StepOutcome.pass() },
            )

        val results = runBlocking { runner.run(steps) }

        assertEquals(listOf(StepStatus.FAILED, StepStatus.PASSED), results.map { it.status })
        assertTrue(results[0].note!!.contains("timed out"))
    }

    private companion object {
        const val SLOW_STEP_MS = 60_000L
    }
}
