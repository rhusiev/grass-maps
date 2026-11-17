package nl.r1a.grassmap.plugins.raster

/**
 * One installed offline area as the sheet lists it (T-0513). The licence comes
 * from the pack's own metadata, so the list shows it with no network.
 */
data class RasterAreaItem(
    val id: String,
    val name: String,
    val bytes: Long,
    val tileCount: Long,
    val minZoom: Int,
    val maxZoom: Int,
    val licence: RasterLicence,
    val attributionHtml: String,
)

/** The state of the one area download the app runs at a time. */
sealed interface RasterAreaDownloadState {
    data object Idle : RasterAreaDownloadState

    data class Running(
        val progress: RasterAreaProgress,
    ) : RasterAreaDownloadState

    data class Failed(
        val reason: RasterAreaFailure,
    ) : RasterAreaDownloadState

    data object Done : RasterAreaDownloadState
}

/** Why a download did not finish, in the terms the sheet words. */
enum class RasterAreaFailure {
    SwitchOff,
    Network,
    Refused,
    NotEnoughSpace,
    Cancelled,
    Other,
}
