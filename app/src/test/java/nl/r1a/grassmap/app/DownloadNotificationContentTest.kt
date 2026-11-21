package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.plugin.api.model.DownloadTransfer
import nl.r1a.grassmaps.plugin.api.model.DownloadTransferState
import nl.r1a.grassmaps.plugin.api.model.MobileDataPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadNotificationContentTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun noTransferStopsTheService() {
        assertNull(downloadNotificationContent(emptyList()))
    }

    @Test
    fun waitingTransfersReportCountAndRemainingMegabytes() {
        val transfers =
            listOf(
                DownloadTransfer("a", DownloadTransferState.WAITING_FOR_WIFI, bytesTotal = 100_000_000),
                DownloadTransfer("b", DownloadTransferState.WAITING_FOR_WIFI, bytesDone = 0, bytesTotal = 50_000_000),
            )

        assertEquals(
            DownloadNotificationContent.WaitingForWifi(maps = 2, megabytes = 150),
            downloadNotificationContent(transfers),
        )
    }

    @Test
    fun aWaitingTransferWinsOverADownloadingOne() {
        val transfers =
            listOf(
                DownloadTransfer("a", DownloadTransferState.DOWNLOADING, bytesDone = 5, bytesTotal = 10),
                DownloadTransfer("b", DownloadTransferState.WAITING_FOR_WIFI, bytesTotal = 1_000_000),
            )

        assertEquals(
            DownloadNotificationContent.WaitingForWifi(maps = 1, megabytes = 1),
            downloadNotificationContent(transfers),
        )
    }

    @Test
    fun downloadingReportsPercentOrNullWithoutASize() {
        val sized = listOf(DownloadTransfer("a", DownloadTransferState.DOWNLOADING, bytesDone = 25, bytesTotal = 100))
        val unsized = listOf(DownloadTransfer("a", DownloadTransferState.WAITING))

        assertEquals(DownloadNotificationContent.Downloading(1, 25), downloadNotificationContent(sized))
        assertEquals(DownloadNotificationContent.Downloading(1, null), downloadNotificationContent(unsized))
    }

    @Test
    fun policyRoundTripsAndDefaultsToWifiOnly() {
        val file = File(folder.root, "settings/downloads.json")
        val settings = DownloadsSettings(file)

        assertEquals(MobileDataPolicy.WIFI_ONLY, settings.load())
        settings.save(MobileDataPolicy.ANY)
        assertEquals(MobileDataPolicy.ANY, DownloadsSettings(file).load())
        file.writeText("garbage")
        assertEquals(MobileDataPolicy.WIFI_ONLY, settings.load())
    }
}
