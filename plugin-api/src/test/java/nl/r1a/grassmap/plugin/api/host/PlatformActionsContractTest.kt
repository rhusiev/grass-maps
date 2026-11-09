package nl.r1a.grassmap.plugin.api.host

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The platform-actions contract (T-0210): a host without an Android
 * implementation is a typed failure on every call, never a crash.
 */
class PlatformActionsContractTest {
    @Test
    fun theInertActionsAreNotReadyForEveryCall() {
        assertEquals(Outcome.Failure(AppError.NotReady), NoPlatformActions.copyToClipboard("49.821740, 24.023910"))
        assertEquals(Outcome.Failure(AppError.NotReady), NoPlatformActions.shareText("49.821740, 24.023910"))
        assertEquals(
            Outcome.Failure(AppError.NotReady),
            NoPlatformActions.shareText("49.821740, 24.023910", "Аптека"),
        )
        assertEquals(Outcome.Failure(AppError.NotReady), NoPlatformActions.openUri("geo:49.821740,24.023910"))
    }
}
