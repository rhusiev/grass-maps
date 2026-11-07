package nl.r1a.grassmap.plugin.api.host

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DevToolsAccessTest {
    @Test
    fun theInertAccessRefusesAndReportsAnEmptySample() =
        runTest {
            assertFalse(NoDevToolsAccess.isReplayActive())
            assertEquals(DevToolsMetrics(framesPerSecond = 0.0, usedMemoryBytes = 0L), NoDevToolsAccess.metrics())
            assertEquals(Outcome.Failure(AppError.Unsupported), NoDevToolsAccess.startReplay())
            NoDevToolsAccess.stopReplay()
        }
}
