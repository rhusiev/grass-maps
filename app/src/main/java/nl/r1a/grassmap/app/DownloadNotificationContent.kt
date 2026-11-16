package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.model.DownloadTransfer
import nl.r1a.grassmap.plugin.api.model.DownloadTransferState

/** What the download notification shows, before any string is resolved. */
internal sealed interface DownloadNotificationContent {
    /** Transfers are held back for an unmetered network. */
    data class WaitingForWifi(
        val maps: Int,
        val megabytes: Long,
    ) : DownloadNotificationContent

    /** Transfers are queued or arriving. [percent] is null when no size is known. */
    data class Downloading(
        val maps: Int,
        val percent: Int?,
    ) : DownloadNotificationContent
}

private const val BYTES_PER_MEGABYTE = 1_000_000L
private const val PERCENT_SCALE = 100L

/**
 * Chooses the notification text for [transfers], or null when nothing is in
 * flight and the service has to stop. A waiting transfer wins: the user has to
 * act on it, and the "Download now on mobile data" action belongs to it.
 */
internal fun downloadNotificationContent(transfers: List<DownloadTransfer>): DownloadNotificationContent? {
    if (transfers.isEmpty()) {
        return null
    }
    val waiting = transfers.filter { it.state == DownloadTransferState.WAITING_FOR_WIFI }
    if (waiting.isNotEmpty()) {
        val bytes = waiting.sumOf { it.bytesTotal - it.bytesDone.coerceAtMost(it.bytesTotal) }
        return DownloadNotificationContent.WaitingForWifi(
            maps = waiting.size,
            megabytes = (bytes + BYTES_PER_MEGABYTE - 1) / BYTES_PER_MEGABYTE,
        )
    }
    val total = transfers.sumOf { it.bytesTotal }
    val done = transfers.sumOf { it.bytesDone.coerceAtMost(it.bytesTotal) }
    val percent = if (total > 0) (done * PERCENT_SCALE / total).toInt() else null
    return DownloadNotificationContent.Downloading(maps = transfers.size, percent = percent)
}
