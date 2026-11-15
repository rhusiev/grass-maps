package nl.r1a.grassmap.app

import android.os.Build

/**
 * The one-time Android 13 notification prompt.
 *
 * The navigation service keeps the process alive while guidance runs and the
 * activity is recreated by a theme, locale or font-scale change, so the fact
 * that the dialog was shown must outlive one activity. [AppContainer] owns this
 * object, and [shouldAsk] answers yes only until the first guidance state asks:
 * a grant, a refusal and a system older than 13 all answer no.
 */
class NotificationPermissionPrompt {
    private var asked = false

    /** Whether the dialog should open now; true at most once per process. */
    fun shouldAsk(
        apiLevel: Int,
        granted: Boolean,
    ): Boolean = apiLevel >= Build.VERSION_CODES.TIRAMISU && !asked && !granted

    /**
     * Records that the dialog was launched. Called before the launch, so a
     * launcher that throws does not retry on every state.
     */
    fun markAsked() {
        asked = true
    }
}
