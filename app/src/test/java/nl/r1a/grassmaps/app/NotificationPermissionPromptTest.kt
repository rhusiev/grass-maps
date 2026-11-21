package nl.r1a.grassmaps.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one-time notification prompt, across the recreations of one process. */
class NotificationPermissionPromptTest {
    @Test
    fun aRecreatedScreenDoesNotAskASecondTime() {
        val prompt = NotificationPermissionPrompt()

        assertTrue(prompt.shouldAsk(API_33, granted = false))
        prompt.markAsked()

        assertFalse("a recreation must not reopen the dialog", prompt.shouldAsk(API_33, granted = false))
    }

    @Test
    fun aGrantedPermissionNeverAsks() {
        assertFalse(NotificationPermissionPrompt().shouldAsk(API_33, granted = true))
    }

    @Test
    fun aSystemOlderThanThirteenNeverAsks() {
        assertFalse(NotificationPermissionPrompt().shouldAsk(API_32, granted = false))
    }

    private companion object {
        const val API_32 = 32
        const val API_33 = 33
    }
}
