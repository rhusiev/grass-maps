package nl.r1a.grassmap.app.devcheck

import android.os.SystemClock

/** The fixed script, in the order the brief lists it. */
internal object DeviceCheckSteps {
    fun build(
        context: CheckContext,
        hidePanel: (Boolean) -> Unit,
    ): List<CheckStep> =
        listOf(
            CheckStep("build_identity") { IdentityStep.run() },
            CheckStep("profile_store") { ProfileStoreSummary.run(context.app.filesDir) },
            CheckStep("download") { DownloadStep(context, SystemClock::elapsedRealtime).run() },
            CheckStep("screenshots") { ScreenshotsStep(context, hidePanel).run() },
            CheckStep("labels") { LabelsStep(context).run() },
            CheckStep("frame_pacing") { PacingStep(context).run() },
        )
}
