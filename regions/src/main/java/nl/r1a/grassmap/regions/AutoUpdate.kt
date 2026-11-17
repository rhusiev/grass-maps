package nl.r1a.grassmap.regions

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.RegionDownloadsAccess
import nl.r1a.grassmap.plugin.api.model.DownloadTransferState
import nl.r1a.grassmap.plugin.api.model.DownloadsData
import nl.r1a.grassmap.plugin.api.model.MobileDataPolicy
import java.time.Clock
import java.time.Duration

/** What a monthly run did, so the caller and the tests can tell the outcomes apart. */
sealed interface AutoUpdateRun {
    /** The user turned automatic updates off. */
    data object Disabled : AutoUpdateRun

    /** The `network.region_updates` switch is off, so nothing was contacted. */
    data object SwitchOff : AutoUpdateRun

    /** The last check is less than [AutoUpdater.CHECK_INTERVAL] old. */
    data object NotDue : AutoUpdateRun

    /** A check failed less than [AutoUpdater.RETRY_INTERVAL] ago. */
    data object BackingOff : AutoUpdateRun

    /** The network is metered and the policy is Wi-Fi only, so nothing was contacted. */
    data object WaitingForWifi : AutoUpdateRun

    /** The catalogue could not be fetched or the updates could not be queued. */
    data object Failed : AutoUpdateRun

    /** The catalogue was fetched and every installed region is current. */
    data object UpToDate : AutoUpdateRun

    /** [regions] maps each queued region to the catalogue version it is moving to. */
    data class Queued(
        val regions: Map<String, String>,
        val checkedAtMs: Long,
    ) : AutoUpdateRun
}

/** The notification the monthly run ends with. Both apply at the next app start. */
sealed interface AutoUpdateNotice {
    /** How many maps the notice is about. */
    val maps: Int

    data class Installed(
        override val maps: Int,
    ) : AutoUpdateNotice

    data class WaitingForWifi(
        override val maps: Int,
    ) : AutoUpdateNotice

    /** The user turned automatic updates off, so an earlier notice no longer holds. */
    data object Withdrawn : AutoUpdateNotice {
        override val maps: Int = 0
    }
}

/** Posts or withdraws an [AutoUpdateNotice]. The Android implementation is a notification. */
fun interface AutoUpdateNotifier {
    fun notify(notice: AutoUpdateNotice)
}

/**
 * The monthly background update (T-0508): when the last check is at least
 * [CHECK_INTERVAL] old, fetches the catalogue through [access] and queues the
 * installed regions that have a newer version. The queue, the Wi-Fi hold, the
 * install beside the old version and the pointer flip are the region store's
 * (T-0505, T-0507), so every move of a transfer still happens under its claim
 * lock. This class only decides whether to start and reports the end.
 *
 * The host calls [runIfDue] from a periodic job, so it must be cheap and safe to
 * call often. It makes no request while automatic updates are off, while the
 * privacy switch is off, or on a metered network under the Wi-Fi only policy.
 * A failed attempt is retried at the first call after [RETRY_INTERVAL].
 */
class AutoUpdater(
    private val access: RegionDownloadsAccess,
    private val ledger: AutoUpdateLedger,
    private val clock: Clock,
    private val isSwitchOn: () -> Boolean,
    private val notifier: AutoUpdateNotifier,
    private val log: (String) -> Unit = {},
) {
    // Every read-modify-write of the ledger runs under this lock, so the run and
    // the announcement of an earlier run never overwrite each other's field.
    private val runMutex = Mutex()

    // The regions this updater queued itself. A region the user had already
    // started is not in it, so turning updates off leaves that transfer alone.
    @Volatile
    private var startedRegions: Set<String> = emptySet()

    /** Checks and queues when a check is due. Call off Main. */
    suspend fun runIfDue(): AutoUpdateRun =
        runMutex.withLock {
            val now = clock.millis()
            val data = access.data.value
            val state = ledger.load()
            val skipped = skipReason(data, state, now)
            if (skipped != null) {
                log("auto-update skipped reason=$skipped")
                return@withLock skipped
            }
            ledger.save(state.copy(lastAttemptMs = now))
            check(now, state)
        }

    private fun skipReason(
        data: DownloadsData,
        state: AutoUpdateLedgerState,
        now: Long,
    ): AutoUpdateRun? =
        when {
            !data.autoUpdate -> AutoUpdateRun.Disabled
            !isSwitchOn() -> AutoUpdateRun.SwitchOff
            !isCheckDue(state.lastCheckMs, now) -> AutoUpdateRun.NotDue
            state.lastAttemptMs?.let { now < it + RETRY_INTERVAL.toMillis() } == true -> AutoUpdateRun.BackingOff
            data.networkMetered && data.mobileDataPolicy == MobileDataPolicy.WIFI_ONLY -> AutoUpdateRun.WaitingForWifi
            else -> null
        }

    private fun isCheckDue(
        lastCheckMs: Long?,
        now: Long,
    ): Boolean {
        val elapsed = now - (lastCheckMs ?: return true)
        // A clock set back must not hold the check off until it catches up.
        return elapsed < 0 || elapsed >= CHECK_INTERVAL.toMillis()
    }

    private suspend fun check(
        now: Long,
        state: AutoUpdateLedgerState,
    ): AutoUpdateRun {
        if (access.refresh() is Outcome.Failure) {
            return AutoUpdateRun.Failed
        }
        val updates = access.data.value.updates
        val alreadyRunning =
            access.data.value.transfers
                .mapTo(mutableSetOf()) { it.region }
        if (updates.isEmpty()) {
            ledger.save(state.copy(lastCheckMs = now, lastAttemptMs = now))
            return AutoUpdateRun.UpToDate
        }
        if (access.updateAll() is Outcome.Failure) {
            return AutoUpdateRun.Failed
        }
        startedRegions = updates.mapNotNullTo(mutableSetOf()) { it.id.takeIf { id -> id !in alreadyRunning } }
        log("auto-update queued regions=${updates.size}")
        return AutoUpdateRun.Queued(updates.associate { it.id to it.version }, checkedAtMs = now)
    }

    /**
     * Waits until the queued updates stop moving, records the check and posts
     * the notice. Transfers held for Wi-Fi count as stopped: the notice says
     * they wait, and a second one follows when they install. A run whose
     * updates all failed records no check, so the next window retries.
     */
    suspend fun announce(run: AutoUpdateRun.Queued) {
        var data = access.data.first { isSettled(it, run) }
        var waiting = waitingCount(data, run)
        if (installedCount(data, run) + waiting > 0) {
            runMutex.withLock { ledger.save(ledger.load().copy(lastCheckMs = run.checkedAtMs)) }
        }
        if (waiting > 0) {
            notifier.notify(AutoUpdateNotice.WaitingForWifi(waiting))
            data = access.data.first { waitingCount(it, run) == 0 }
            waiting = 0
        }
        val installed = installedCount(data, run)
        if (installed > 0 && waiting == 0) {
            notifier.notify(AutoUpdateNotice.Installed(installed))
        }
    }

    /**
     * Cancels the transfers this updater queued and withdraws its notice. The
     * user's own transfers stay. Call it when automatic updates are turned off.
     */
    suspend fun cancelStarted() {
        val regions = startedRegions
        startedRegions = emptySet()
        regions.forEach { region -> access.cancel(region) }
        notifier.notify(AutoUpdateNotice.Withdrawn)
    }

    private fun isSettled(
        data: DownloadsData,
        run: AutoUpdateRun.Queued,
    ): Boolean {
        val states = run.regions.keys.mapNotNull { data.transfer(it)?.state }
        return states.isEmpty() || DownloadTransferState.WAITING_FOR_WIFI in states
    }

    private fun waitingCount(
        data: DownloadsData,
        run: AutoUpdateRun.Queued,
    ): Int = run.regions.keys.count { data.transfer(it) != null }

    private fun installedCount(
        data: DownloadsData,
        run: AutoUpdateRun.Queued,
    ): Int = run.regions.count { (region, version) -> data.installed(region)?.version == version }

    companion object {
        /** A month, as the product decision says: automatic, monthly. */
        val CHECK_INTERVAL: Duration = Duration.ofDays(30)

        /** The pause after a failed attempt, so a failing server is asked once a day at most. */
        val RETRY_INTERVAL: Duration = Duration.ofDays(1)
    }
}
