package nl.r1a.grassmaps.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.r1a.grassmaps.regions.AutoUpdater

/**
 * What the app needs from the system to wake the monthly map update (T-0508).
 * It is an interface so [AppContainer] stays free of Android types.
 */
interface AutoUpdateScheduler {
    /**
     * Makes the system wake the app about once a day while [enabled]. With
     * [wifiOnly] the wake-up waits for an unmetered network. Disabled cancels
     * the wake-up. Calling it again with the same input changes nothing.
     */
    fun apply(
        enabled: Boolean,
        wifiOnly: Boolean,
    )
}

/** The scheduler for a build or a test without a system scheduler. */
object NoAutoUpdateScheduler : AutoUpdateScheduler {
    override fun apply(
        enabled: Boolean,
        wifiOnly: Boolean,
    ) = Unit
}

/**
 * Schedules [AutoUpdateJobService] with the platform [JobScheduler].
 *
 * Why JobScheduler: it is part of Android, so it needs no Google component
 * (F-Droid builds require that), and it enforces the unmetered-network
 * constraint itself, so a job never starts on mobile data. WorkManager is not
 * in the project. The job repeats daily, and [AutoUpdater] decides whether a
 * month has passed. It survives a reboot through `RECEIVE_BOOT_COMPLETED`.
 */
class AndroidAutoUpdateScheduler(
    private val context: Context,
) : AutoUpdateScheduler {
    override fun apply(
        enabled: Boolean,
        wifiOnly: Boolean,
    ) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val pending = scheduler.getPendingJob(JOB_ID)?.networkType?.let { it == JobInfo.NETWORK_TYPE_UNMETERED }
        when (decideSchedule(enabled, wifiOnly, pendingWifiOnly = pending)) {
            ScheduleAction.CANCEL -> scheduler.cancel(JOB_ID)
            ScheduleAction.KEEP -> Unit
            ScheduleAction.SCHEDULE -> scheduler.schedule(buildJob(wifiOnly))
        }
    }

    private fun buildJob(wifiOnly: Boolean): JobInfo {
        val networkType = if (wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY
        return JobInfo
            .Builder(JOB_ID, ComponentName(context, AutoUpdateJobService::class.java))
            .setRequiredNetworkType(networkType)
            .setPeriodic(AutoUpdater.RETRY_INTERVAL.toMillis())
            .setPersisted(true)
            .build()
    }

    private companion object {
        const val JOB_ID = 4508
    }
}

/**
 * The system's wake-up for the monthly update. It runs one [AutoUpdater] window
 * and reports the job finished only when the window ends, so the system keeps
 * the process alive while the queued update downloads. When the system stops
 * the job first, it is rescheduled, and the partial download resumes at the
 * next window with a Range request (T-0505).
 */
class AutoUpdateJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as GrassMapsApplication).container
        scope.launch {
            try {
                container.runAutoUpdateWindow()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "auto-update-window-failed type=${error.javaClass.simpleName}")
            } finally {
                if (isActive) {
                    jobFinished(params, false)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        scope.coroutineContext.cancelChildren()
        return true
    }

    private companion object {
        const val TAG = "AutoUpdate"
    }
}
