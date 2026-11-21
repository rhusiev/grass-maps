package nl.r1a.grassmaps.core.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmaps.plugin.api.contract.SwitchId
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.service.NetworkSwitches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyNetworkGateTest {
    private val switches = PrivacySwitchSettings()
    private val gate = PrivacyNetworkGate(switches)

    @Test
    fun onlineSearchRequestIsRefusedWhileItsSwitchIsOff() =
        runTest {
            val request = RecordingRequest()

            val result = gate.request(NetworkSwitches.onlineSearch) { request.call() }

            assertEquals(
                Outcome.Failure(AppError.SwitchOff(NetworkSwitches.onlineSearch)),
                result,
            )
            assertEquals(0, request.calls)
        }

    @Test
    fun onlineTilesRequestIsRefusedWhileItsSwitchIsOff() =
        runTest {
            val request = RecordingRequest()

            val result = gate.request(NetworkSwitches.onlineTiles) { request.call() }

            assertEquals(
                Outcome.Failure(AppError.SwitchOff(NetworkSwitches.onlineTiles)),
                result,
            )
            assertEquals(0, request.calls)
        }

    @Test
    fun regionUpdatesRequestIsRefusedWhileItsSwitchIsOff() =
        runTest {
            val request = RecordingRequest()

            val result = gate.request(NetworkSwitches.regionUpdates) { request.call() }

            assertEquals(
                Outcome.Failure(AppError.SwitchOff(NetworkSwitches.regionUpdates)),
                result,
            )
            assertEquals(0, request.calls)
        }

    @Test
    fun refusalNamesTheRequestedSwitch() =
        runTest {
            val unregistered = SwitchId("network.unregistered")
            val request = RecordingRequest()

            val result = gate.request(unregistered) { request.call() }

            assertEquals(Outcome.Failure(AppError.SwitchOff(unregistered)), result)
            assertEquals(0, request.calls)
            assertEquals(0L, switches.state(unregistered).requests)
        }

    @Test
    fun enabledRequestRunsTheBlockAndCountsIt() =
        runTest {
            switches.setEnabled(NetworkSwitches.onlineSearch, true)
            val request = RecordingRequest()

            val result = gate.request(NetworkSwitches.onlineSearch) { request.call() }

            assertEquals(Outcome.Success("response"), result)
            assertEquals(1, request.calls)
            assertEquals(1L, switches.state(NetworkSwitches.onlineSearch).requests)
        }

    @Test
    fun enablingOneSwitchLeavesTheOtherSwitchesRefused() =
        runTest {
            switches.setEnabled(NetworkSwitches.onlineSearch, true)
            val request = RecordingRequest()

            val result = gate.request(NetworkSwitches.onlineTiles) { request.call() }

            assertEquals(
                Outcome.Failure(AppError.SwitchOff(NetworkSwitches.onlineTiles)),
                result,
            )
            assertEquals(0, request.calls)
            assertEquals(0L, switches.state(NetworkSwitches.onlineTiles).requests)
        }

    @Test
    fun failedRequestStillCountsAsAllowedContact() =
        runTest {
            switches.setEnabled(NetworkSwitches.regionUpdates, true)
            val request = RecordingRequest(result = Outcome.Failure(AppError.Network))

            val result = gate.request(NetworkSwitches.regionUpdates) { request.call() }

            assertEquals(Outcome.Failure(AppError.Network), result)
            assertEquals(1L, switches.state(NetworkSwitches.regionUpdates).requests)
        }

    @Test
    fun turningASwitchOffStopsFurtherRequests() =
        runTest {
            switches.setEnabled(NetworkSwitches.onlineTiles, true)
            val request = RecordingRequest()
            gate.request(NetworkSwitches.onlineTiles) { request.call() }

            switches.setEnabled(NetworkSwitches.onlineTiles, false)
            val refused = gate.request(NetworkSwitches.onlineTiles) { request.call() }

            assertEquals(
                Outcome.Failure(AppError.SwitchOff(NetworkSwitches.onlineTiles)),
                refused,
            )
            assertEquals(1, request.calls)
            assertEquals(1L, switches.state(NetworkSwitches.onlineTiles).requests)
        }

    @Test
    fun blockExceptionBecomesNetworkFailureAndIsCountedOnce() =
        runTest {
            switches.setEnabled(NetworkSwitches.onlineSearch, true)

            val result =
                gate.request<String>(NetworkSwitches.onlineSearch) {
                    throw IllegalStateException("boom")
                }

            assertEquals(Outcome.Failure(AppError.Network), result)
            assertEquals(1L, switches.state(NetworkSwitches.onlineSearch).requests)
        }

    @Test
    fun cancellationFromTheBlockPropagates() =
        runTest {
            switches.setEnabled(NetworkSwitches.onlineTiles, true)
            val cancellation = CancellationException("cancelled")

            var thrown: CancellationException? = null
            try {
                gate.request<String>(NetworkSwitches.onlineTiles) { throw cancellation }
            } catch (exception: CancellationException) {
                thrown = exception
            }

            assertOriginalOrCopyOf(cancellation, thrown)
        }

    @Test
    fun errorFromTheBlockIsNotCaught() =
        runTest {
            switches.setEnabled(NetworkSwitches.regionUpdates, true)
            val error = AssertionError("boom")

            var thrown: Throwable? = null
            try {
                gate.request<String>(NetworkSwitches.regionUpdates) { throw error }
            } catch (caught: Throwable) {
                thrown = caught
            }

            assertOriginalOrCopyOf(error, thrown)
        }

    @Test
    fun turningTheSwitchOffStopsARequestInFlightAtItsNextCheck() =
        runTest {
            switches.setEnabled(NetworkSwitches.onlineSearch, true)
            var reachedAfterSwitchOff = false

            val result =
                gate.request<String>(NetworkSwitches.onlineSearch) {
                    switches.setEnabled(NetworkSwitches.onlineSearch, false)
                    currentCoroutineContext().ensureActive()
                    reachedAfterSwitchOff = true
                    Outcome.Success("response")
                }

            assertEquals(
                Outcome.Failure(AppError.SwitchOff(NetworkSwitches.onlineSearch)),
                result,
            )
            assertEquals(false, reachedAfterSwitchOff)
            assertEquals(1L, switches.state(NetworkSwitches.onlineSearch).requests)
        }

    @Test
    fun turningAnotherSwitchOffLeavesARequestInFlightRunning() =
        runTest {
            switches.setEnabled(NetworkSwitches.onlineSearch, true)
            switches.setEnabled(NetworkSwitches.onlineTiles, true)

            val result =
                gate.request(NetworkSwitches.onlineSearch) {
                    switches.setEnabled(NetworkSwitches.onlineTiles, false)
                    currentCoroutineContext().ensureActive()
                    Outcome.Success("response")
                }

            assertEquals(Outcome.Success("response"), result)
        }

    @Test
    fun aCallerCancellationStillPropagatesWhileTheSwitchStaysOn() =
        runTest {
            switches.setEnabled(NetworkSwitches.onlineTiles, true)
            val job =
                launch {
                    gate.request<String>(NetworkSwitches.onlineTiles) { awaitCancellation() }
                }
            runCurrent()

            job.cancel()
            job.join()

            assertEquals(true, job.isCancelled)
            assertEquals(1L, switches.state(NetworkSwitches.onlineTiles).requests)
        }

    /** Coroutine stack-trace recovery may rethrow a copy whose cause is the original. */
    private fun assertOriginalOrCopyOf(
        original: Throwable,
        thrown: Throwable?,
    ) {
        assertTrue(
            "expected the original exception or a copy caused by it, got $thrown",
            thrown === original || (thrown != null && thrown.cause === original),
        )
    }

    private class RecordingRequest(
        private val result: Outcome<String> = Outcome.Success("response"),
    ) {
        var calls: Int = 0
            private set

        suspend fun call(): Outcome<String> {
            calls++
            return result
        }
    }
}
