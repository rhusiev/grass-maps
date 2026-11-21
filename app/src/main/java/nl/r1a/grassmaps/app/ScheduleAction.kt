package nl.r1a.grassmaps.app

/** What to do with the system's wake-up job after a choice changed. */
enum class ScheduleAction {
    /** Remove the job: automatic updates are off. */
    CANCEL,

    /** Leave the pending job alone, so its period keeps running. */
    KEEP,

    /** Schedule the job, replacing a pending one with another network type. */
    SCHEDULE,
}

/**
 * Decides the scheduler action. [pendingWifiOnly] is null without a pending job,
 * true for a job that needs an unmetered network, false for one that accepts any.
 * Scheduling again would restart the period, so a matching job stays.
 */
fun decideSchedule(
    enabled: Boolean,
    wifiOnly: Boolean,
    pendingWifiOnly: Boolean?,
): ScheduleAction =
    when {
        !enabled -> ScheduleAction.CANCEL
        pendingWifiOnly == wifiOnly -> ScheduleAction.KEEP
        else -> ScheduleAction.SCHEDULE
    }
