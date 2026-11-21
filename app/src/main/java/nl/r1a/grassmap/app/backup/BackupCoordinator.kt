package nl.r1a.grassmaps.app.backup

import nl.r1a.grassmaps.alerts.AlertSettingsStore
import nl.r1a.grassmaps.app.FavouritesAccess
import nl.r1a.grassmaps.app.MapStyleSettings
import nl.r1a.grassmaps.app.TrackAccess
import nl.r1a.grassmaps.core.PluginHost
import nl.r1a.grassmaps.core.services.OnlineSearchSettings
import nl.r1a.grassmaps.core.services.ProfileCatalog
import nl.r1a.grassmaps.core.services.RoutingOptionsSettings
import nl.r1a.grassmaps.core.services.SearchHistorySettings
import nl.r1a.grassmaps.core.services.backup.BACKUP_FORMAT_VERSION
import nl.r1a.grassmaps.core.services.backup.BackupBundle
import nl.r1a.grassmaps.core.services.backup.BackupImporter
import nl.r1a.grassmaps.core.services.backup.BundleConflictDecisions
import nl.r1a.grassmaps.core.services.backup.BundleImportSummary
import nl.r1a.grassmaps.core.services.backup.BundleKind
import nl.r1a.grassmaps.core.services.backup.BundleKindSource
import nl.r1a.grassmaps.core.services.backup.BundleZipWriter
import nl.r1a.grassmaps.core.services.backup.PreparedBackup
import nl.r1a.grassmaps.places.SearchHistoryStore
import nl.r1a.grassmaps.plugin.api.contract.PluginId
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.service.Clock
import nl.r1a.grassmaps.plugin.api.service.SystemClock
import nl.r1a.grassmaps.routing.RouteHistoryStore
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** One kind and its record count, for the screen and the manifest. */
data class BackupKindCount(
    val kind: String,
    val count: Int,
)

/** The written bundle and what it holds. */
class BackupExport(
    val bytes: ByteArray,
    val counts: List<BackupKindCount>,
)

/**
 * Ties the bundle format to the app's stores (T-0608/T-0609). Every kind reads
 * and writes through the store that owns it, so the bundle never becomes a
 * second copy of a store and never writes beside a region pack.
 */
class BackupCoordinator(
    private val tracks: () -> TrackAccess,
    favourites: FavouritesAccess,
    profileCatalog: ProfileCatalog,
    routing: RoutingOptionsSettings,
    alerts: AlertSettingsStore,
    private val searchHistorySettings: SearchHistorySettings,
    onlineSearchSettings: OnlineSearchSettings,
    searchHistory: SearchHistoryStore,
    routeHistory: RouteHistoryStore,
    mapStyle: MapStyleSettings,
    pluginHost: PluginHost,
    switchablePluginIds: Set<PluginId>,
    private val appVersion: String,
    private val clock: Clock = SystemClock,
) {
    private val sources: List<BundleKindSource> =
        listOf(
            TrackBundleKind(tracks),
            FavouritesBundleKind(favourites),
            ProfileBundleKind(profileCatalog),
            RoutingBundleKind(routing),
            AlertsBundleKind(alerts),
            PrivacySwitchesBundleKind(searchHistorySettings, onlineSearchSettings),
            SearchHistoryBundleKind(searchHistory, searchHistorySettings),
            RouteHistoryBundleKind(routeHistory, searchHistorySettings),
            MapStyleBundleKind(mapStyle),
            PluginSwitchesBundleKind(pluginHost, switchablePluginIds),
        )

    private val importer = BackupImporter(sources)

    /** The counts the screen shows before anything is written. */
    suspend fun summary(): Outcome<List<BackupKindCount>> {
        val counts = mutableListOf<BackupKindCount>()
        for (source in sources) {
            when (val count = source.count()) {
                is Outcome.Success -> counts += BackupKindCount(source.kind, count.value)
                is Outcome.Failure -> return count
            }
        }
        return Outcome.Success(counts)
    }

    /** Builds one bundle in memory. A kind that cannot be read fails the whole export. */
    suspend fun export(): Outcome<BackupExport> {
        val kinds = mutableListOf<BundleKind>()
        val counts = mutableListOf<BackupKindCount>()
        for (source in sources) {
            val entries =
                when (val read = source.read()) {
                    is Outcome.Success -> read.value
                    is Outcome.Failure -> return read
                }
            val files =
                when (val read = source.files()) {
                    is Outcome.Success -> read.value
                    is Outcome.Failure -> return read
                }
            kinds += BundleKind(source.kind, entries, files)
            counts += BackupKindCount(source.kind, entries.size)
        }
        val bundle =
            BackupBundle(
                formatVersion = BACKUP_FORMAT_VERSION,
                appVersion = appVersion,
                createdAtMillis = clock.nowMillis(),
                kinds = kinds,
            )
        val output = ByteArrayOutputStream()
        return when (val written = BundleZipWriter.write(bundle, output)) {
            is Outcome.Success -> Outcome.Success(BackupExport(output.toByteArray(), counts))
            is Outcome.Failure -> written
        }
    }

    suspend fun prepare(bytes: ByteArray): Outcome<PreparedBackup> = importer.prepare(bytes)

    suspend fun prepare(input: InputStream): Outcome<PreparedBackup> = importer.prepare(input)

    suspend fun apply(
        prepared: PreparedBackup,
        decisions: BundleConflictDecisions,
    ): Outcome<BundleImportSummary> = importer.apply(prepared, decisions)

    /** The display order of the kinds, from the one list the writer uses. */
    fun kindOrder(): List<String> = sources.map { source -> source.kind }
}
