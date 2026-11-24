package nl.r1a.grassmaps.plugins.downloads.ui

import nl.r1a.grassmaps.plugin.api.model.DownloadProblemKind
import nl.r1a.grassmaps.plugin.api.model.DownloadsState

/**
 * The sentence one typed problem shows (T-0504).
 *
 * A 401 is two different situations and gets two different sentences: no token
 * was sent, so the server asks for one, or a token was sent and refused. The
 * mapping is kept out of the Compose content so a test can state it without a
 * renderer.
 */
internal object DownloadsProblemText {
    /** A failed catalog request is not a failed transfer, even when both have the same cause. */
    fun catalogKey(state: DownloadsState): Int? {
        if (!state.downloadsEnabled) {
            return null
        }
        return when (val kind = state.catalogProblem?.kind) {
            null, DownloadProblemKind.SWITCH_OFF -> {
                when {
                    state.catalogLoaded -> null
                    state.query.isBlank() -> R.string.downloads_empty
                    else -> R.string.downloads_catalog_unavailable
                }
            }

            DownloadProblemKind.NETWORK -> {
                if (state.catalogLoaded) R.string.downloads_no_connection else R.string.downloads_catalog_offline
            }

            DownloadProblemKind.CORRUPT_DATA, DownloadProblemKind.STORAGE, DownloadProblemKind.OTHER, DownloadProblemKind.UPDATE_FAILED -> {
                R.string.downloads_catalog_unavailable
            }

            else -> {
                key(kind)
            }
        }
    }

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
