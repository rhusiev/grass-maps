package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.host.RegionDownloadsAccess

/**
 * The region store plus the privacy switch that gates it (T-0660).
 *
 * A screen reads the same switch the network gate enforces and writes through
 * the app's switch writer, so it gets no second way to grant network access.
 * A host without a writer keeps the interface's typed NotReady failure.
 */
internal class GatedDownloadsAccess(
    private val store: RegionDownloadsAccess,
    private val isEnabled: () -> Boolean,
    private val setEnabled: suspend (Boolean) -> Outcome<Unit>,
) : RegionDownloadsAccess by store {
    override val downloadsEnabled: Boolean
        get() = isEnabled()

    override suspend fun setDownloadsEnabled(enabled: Boolean): Outcome<Unit> = setEnabled(enabled)
}
