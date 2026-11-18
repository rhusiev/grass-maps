package nl.r1a.grassmap.app.devcheck

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.CatalogRegion
import nl.r1a.grassmap.plugin.api.model.CatalogRegionKind
import nl.r1a.grassmap.plugin.api.model.DownloadsData
import nl.r1a.grassmap.regions.RegionStore

private const val DOWNLOAD_TIMEOUT_MS = 10 * 60 * 1000L
private const val POLL_INTERVAL_MS = 500L

/**
 * One real download of the smallest oblast in the live catalogue through the
 * normal store path, with the embedded or user token. It runs on Wi-Fi only,
 * and it removes the pack afterwards unless it was installed before.
 */
internal class DownloadStep(
    private val context: CheckContext,
    private val clockMs: () -> Long,
) {
    suspend fun run(): StepOutcome {
        val store = context.container.regionStore ?: return StepOutcome.skip("no region store in this build")
        if (!isOnWifi()) {
            return StepOutcome.skip("not on Wi-Fi: the check does not download on mobile data")
        }
        if (!store.data.value.tokenPresent) {
            return StepOutcome.skip("no region-server token: embed one at build time or enter one in Maps settings")
        }
        val switchWasOn = context.container.regionDownloadsEnabled
        if (!switchWasOn) {
            context.container.setRegionDownloadsEnabled(true)
        }
        try {
            return download(store)
        } finally {
            if (!switchWasOn) {
                withContext(NonCancellable) { context.container.setRegionDownloadsEnabled(false) }
            }
        }
    }

    private suspend fun download(store: RegionStore): StepOutcome {
        val refreshed = store.refresh()
        if (refreshed is Outcome.Failure) {
            return StepOutcome.fail("catalogue refresh failed: ${refreshed.error}")
        }
        val entries = store.data.value.catalog?.entries().orEmpty()
        val smallest =
            entries.filter { it.kind == CatalogRegionKind.REGION }.minByOrNull { it.bytes }
                ?: return StepOutcome.fail("the catalogue has no region", mapOf("catalog_entries" to JsonPrimitive(entries.size)))
        if (store.data.value.installed(smallest.id) != null) {
            return StepOutcome.skip("${smallest.id} was already installed, so the check leaves it alone")
        }
        return try {
            installAndVerify(store, smallest, entries.size)
        } finally {
            withContext(NonCancellable) { store.remove(smallest.id) }
        }
    }

    private suspend fun installAndVerify(
        store: RegionStore,
        region: CatalogRegion,
        catalogEntries: Int,
    ): StepOutcome {
        val startedAt = clockMs()
        val started = store.startDownload(region.id)
        if (started is Outcome.Failure) {
            return StepOutcome.fail("start failed: ${started.error}")
        }
        val finished = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) { awaitEnd(store, region.id) }
        val elapsed = clockMs() - startedAt
        val installed = store.data.value.installed(region.id)
        val numbers =
            mapOf(
                "region" to JsonPrimitive(region.id),
                "catalog_entries" to JsonPrimitive(catalogEntries),
                "catalog_bytes" to JsonPrimitive(region.bytes),
                "installed_bytes" to JsonPrimitive(installed?.bytes ?: 0L),
                "version" to JsonPrimitive(region.version),
                "download_ms" to JsonPrimitive(elapsed),
            )
        return when {
            finished == null -> StepOutcome.fail("timed out after $DOWNLOAD_TIMEOUT_MS ms", numbers)
            installed == null -> StepOutcome.fail("download ended without an installed pack: ${finished.orEmpty()}", numbers)
            installed.version != region.version -> StepOutcome.fail("installed version ${installed.version} differs from the catalogue", numbers)
            installed.bytes <= 0L -> StepOutcome.fail("installed pack is empty", numbers)
            else -> StepOutcome.pass(numbers)
        }
    }

    /** Returns "" when the pack installed, or the problem text when the store recorded one. */
    private suspend fun awaitEnd(
        store: RegionStore,
        region: String,
    ): String? {
        while (true) {
            val data: DownloadsData = store.data.value
            val problem = data.problem(region)
            when {
                data.installed(region) != null && data.transfer(region) == null -> return ""
                problem != null -> return problem.toString()
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun isOnWifi(): Boolean {
        val manager = context.app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
