package nl.r1a.grassmap.plugins.downloads.ui

import nl.r1a.grassmap.plugin.api.model.DownloadProblemKind

/**
 * The sentence one typed problem shows (T-0504).
 *
 * A 401 is two different situations and gets two different sentences: no token
 * was sent, so the server asks for one, or a token was sent and refused. The
 * mapping is kept out of the Compose content so a test can state it without a
 * renderer.
 */
internal object DownloadsProblemText {
    fun key(kind: DownloadProblemKind): Int =
        when (kind) {
            DownloadProblemKind.TOKEN_REQUIRED -> R.string.downloads_token_required
            DownloadProblemKind.TOKEN_REJECTED -> R.string.downloads_token_rejected
            DownloadProblemKind.SWITCH_OFF -> R.string.downloads_failed_switch_off
            DownloadProblemKind.NETWORK -> R.string.downloads_failed_network
            DownloadProblemKind.SERVER -> R.string.downloads_failed_server
            DownloadProblemKind.CORRUPT_DATA -> R.string.downloads_failed_corrupt
            DownloadProblemKind.UNSUPPORTED -> R.string.downloads_failed_unsupported
            DownloadProblemKind.UPDATE_FAILED -> R.string.downloads_update_failed
            DownloadProblemKind.STORAGE, DownloadProblemKind.OTHER -> R.string.downloads_failed_other
        }
}
