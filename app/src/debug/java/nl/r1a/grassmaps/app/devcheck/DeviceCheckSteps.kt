package nl.r1a.grassmaps.app.devcheck

import android.os.SystemClock

private const val DOWNLOAD_STEP_TIMEOUT_MS = 12 * 60 * 1000L
private const val LABELS_STEP_TIMEOUT_MS = 4 * 60 * 1000L

/** The fixed script, in the order the brief lists it. */
internal object DeviceCheckSteps {
    fun build(
        context: CheckContext,
        hidePanel: (Boolean) -> Unit,
    ): List<CheckStep> =
        listOf(
            CheckStep("build_identity") { IdentityStep.run(context.app) },
            CheckStep("profile_store") { SettingsDump.run(context.app.filesDir) },
            CheckStep("download", DOWNLOAD_STEP_TIMEOUT_MS) { DownloadStep(context, SystemClock::elapsedRealtime).run() },
            CheckStep("route_plan", PLAN_TIMEOUT_MS * 2) { PlanStep(context).run() },
            CheckStep("navigation", NAV_START_TIMEOUT_MS * 2) { NavigationStep(context).run() },
            CheckStep("screenshots") { ScreenshotsStep(context, hidePanel).run() },
            CheckStep("labels", LABELS_STEP_TIMEOUT_MS) { LabelsStep(context).run() },
            CheckStep("frame_pacing") { PacingStep(context).run() },
            CheckStep("cleanup") { CleanupStep(context).run() },
        )
}
