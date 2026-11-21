package nl.r1a.grassmaps.core.services.backup

import kotlinx.serialization.json.JsonObject

/**
 * The kinds of user data a bundle carries (T-0608). The id is also the folder
 * name, one folder per kind, the same shape a region pack uses.
 *
 * A reader this build does not know is skipped with a warning, so a newer
 * bundle still restores the kinds an older app understands.
 */
object BackupKinds {
    const val TRACKS: String = "tracks"
    const val FAVOURITES: String = "favourites"
    const val PROFILES: String = "profiles"
    const val ROUTING: String = "routing"
    const val ALERTS: String = "alerts"
    const val PRIVACY: String = "privacy"
    const val SEARCH_HISTORY: String = "search-history"
    const val ROUTE_HISTORY: String = "route-history"
    const val MAP_STYLE: String = "map-style"
    const val PLUGINS: String = "plugins"

    /** Every kind this build reads and writes, in the order the screen lists them. */
    val ALL: List<String> =
        listOf(
            TRACKS,
            FAVOURITES,
            PROFILES,
            ROUTING,
            ALERTS,
            PRIVACY,
            SEARCH_HISTORY,
            ROUTE_HISTORY,
            MAP_STYLE,
            PLUGINS,
        )
}

/** The format version this build reads and writes. */
const val BACKUP_FORMAT_VERSION: Int = 1

/** The manifest's format name; a ZIP without it is not a bundle. */
const val BACKUP_FORMAT_ID: String = "grass-maps-backup"

/** One attachment of an entry, such as a track's GPX document. */
class BundleFile(
    val path: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is BundleFile && path == other.path && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * path.hashCode() + bytes.contentHashCode()
}

/**
 * One record of a kind. [id] is the store's own identity, so a merge by id can
 * tell an addition from a conflict. [value] is the record's JSON; [files] are
 * attachments the value names by path.
 */
data class BundleEntry(
    val id: String,
    val value: JsonObject,
    val files: List<BundleFile> = emptyList(),
)

/** One kind's records, in the store's order. [files] are kind-wide attachments that are not an entry. */
data class BundleKind(
    val id: String,
    val entries: List<BundleEntry>,
    val files: List<BundleFile> = emptyList(),
)

/** A warning from reading, so the import can tell the user what it skipped. */
sealed interface BackupWarning {
    /** A kind this build does not know was skipped whole. */
    data class UnknownKind(
        val kind: String,
    ) : BackupWarning
}

/** A readable bundle: the manifest facts and the kinds this build understands. */
data class BackupBundle(
    val formatVersion: Int,
    val appVersion: String,
    val createdAtMillis: Long,
    val kinds: List<BundleKind>,
    val warnings: List<BackupWarning> = emptyList(),
) {
    fun kind(id: String): BundleKind? = kinds.firstOrNull { kind -> kind.id == id }
}

/** One thing the bundle deliberately leaves out, stated in the manifest. */
data class ExcludedItem(
    val id: String,
    val description: String,
)

/**
 * What every bundle leaves out (T-0608, the privacy rules). The list is written
 * into the manifest, so a reader of the file itself knows what it does not hold.
 */
val BACKUP_EXCLUSIONS: List<ExcludedItem> =
    listOf(
        ExcludedItem("tokens", "The region-server token, the control-channel token, every other token or key"),
        ExcludedItem("logs", "Logs, diagnostic bundles and crash reports"),
        ExcludedItem("regions", "Downloaded region packages and the external search index"),
        ExcludedItem("units", "Length units and map label settings"),
        ExcludedItem("layers", "The contour, raster, button and widget layouts"),
        ExcludedItem(
            "network_switches",
            "Session-only network switches that this build does not persist",
        ),
    )
