package nl.r1a.grassmap.plugin.api.model

import kotlinx.serialization.Serializable

/**
 * The fixed values the downloads screen starts from.
 *
 * The public build ships with our map server filled in and no token
 * (server-protocol.md section 1, round 7); a self-hosted server uses the same
 * layout. Neither value is a secret.
 */
object DownloadsDefaults {
    const val SERVER_URL: String = "https://grassmaps.r1a.nl"

    /** The catalogue file, relative to the server base URL. */
    const val CATALOG_FILE: String = "catalog.json"

    /** One archive per region version, relative to the region and version. */
    const val PACKAGE_FILE: String = "package.zip"

    /** A catalogue is small; anything larger is not one. */
    const val MAX_CATALOG_BYTES: Long = 4L * 1024L * 1024L
}

/** What a catalogue entry is, as the server declares it. */
@Serializable
enum class CatalogRegionKind {
    /** The whole-world overview, drawn when zoomed out and where no oblast is loaded. */
    WORLD,

    /** One oblast or country package. */
    REGION,

    /** A kind this build does not know; it is still shown and downloadable. */
    OTHER,
}

/**
 * One part of a region package. [kind] is the first of the five the pipeline
 * builds; [wireKind] keeps the server's own name, so an unknown part round-trips
 * instead of being guessed from its position.
 *
 * [bytes] is the part's size in the archive, which is what the downloads sheet
 * adds up. The archive itself is one file per region version, so a part is
 * never fetched on its own.
 */
@Serializable
data class CatalogPart(
    val kind: CatalogPartKind,
    val wireKind: String,
    val version: String,
    val bytes: Long,
)

/** The part kinds the app names; [OTHER] is any kind a newer server adds. */
@Serializable
enum class CatalogPartKind {
    MAP,
    CONTOURS,
    SEARCH,
    ELEVATION,
    ROUTING,

    /** The optional 30 m elevation grid, an add-on rather than a base part. */
    ELEVATION_30M,
    OTHER,
    ;

    /** True for the kinds an add-on may have. Any other add-on id is not offered. */
    val isKnownAddon: Boolean
        get() = this == ELEVATION_30M

    companion object {
        /** The wire name of the 30 m elevation add-on (server-protocol.md section 2). */
        const val ELEVATION_30M_WIRE: String = "elevation-30m"

        /** Maps a catalogue `kind`, an add-on `id` or a component kind to the kind the screen draws. */
        fun fromWire(wire: String): CatalogPartKind =
            when {
                wire == "map" -> MAP

                wire == "contours" -> CONTOURS

                wire == "elevation" -> ELEVATION

                // A package carries the search section as several components
                // (`search-terms`, `search-places`, ...), one part to the screen.
                wire == "search" || wire.startsWith("search-") -> SEARCH

                wire == "routing" -> ROUTING

                wire == ELEVATION_30M_WIRE -> ELEVATION_30M

                else -> OTHER
            }
    }
}

/** An optional pack offered next to a region, such as the 30 m elevation grid. */
@Serializable
data class CatalogAddon(
    val id: String,
    val version: String,
    val bytes: Long,
) {
    val kind: CatalogPartKind
        get() = CatalogPartKind.fromWire(id)

    /**
     * The package id of this add-on for [region]: the name its directory and
     * its server path carry, and the id the downloads API takes (T-0509).
     */
    fun packageId(region: String): String = "$region-$id"
}

/** What a download id stands for in the catalogue: the version and size to fetch. */
data class CatalogDownload(
    val id: String,
    val version: String,
    val bytes: Long,
    /** The region an add-on belongs to, or null for a region package. */
    val addonOf: String? = null,
)

/**
 * One catalogue entry (server-protocol.md section 2).
 *
 * [bbox] is the declared box as west, south, east, north, the order the
 * catalogue and the manifest both use; [dataDate] is the ISO date of the
 * region's data, taken from [version] when the version names a date. [nameEn]
 * and [nameUk] travel together so the screen can draw either language without a
 * second lookup.
 */
@Serializable
data class CatalogRegion(
    val id: String,
    val nameEn: String? = null,
    val nameUk: String? = null,
    val kind: CatalogRegionKind = CatalogRegionKind.REGION,
    val parent: String? = null,
    val version: String,
    val bytes: Long,
    val bbox: List<Double> = emptyList(),
    val outline: String? = null,
    val parts: List<CatalogPart> = emptyList(),
    val addons: List<CatalogAddon> = emptyList(),
    val dataDate: String? = null,
) {
    /** The bbox as a filterable box, or null when the server sent none or a bad one. */
    val bounds: BoundingBox?
        get() {
            if (bbox.size != BBOX_COORDINATES) {
                return null
            }
            val west = bbox[0]
            val south = bbox[1]
            val east = bbox[2]
            val north = bbox[3]
            val box = BoundingBox(GeoPoint(south, west), GeoPoint(north, east))
            return box.takeIf { it.isValid() }
        }

    /** The name in the user's language, falling back to the other one. */
    fun name(preferUkrainian: Boolean): String? =
        if (preferUkrainian) {
            nameUk ?: nameEn
        } else {
            nameEn ?: nameUk
        }

    /** True when this entry is the whole-world overview. */
    val isWorld: Boolean
        get() = kind == CatalogRegionKind.WORLD

    private companion object {
        const val BBOX_COORDINATES = 4
    }
}

/** One catalogue as the server publishes it. */
@Serializable
data class RegionCatalog(
    val format: Int,
    val generated: String? = null,
    val attribution: List<String> = emptyList(),
    val regions: List<CatalogRegion> = emptyList(),
) {
    /** The whole-world entry, when the catalogue carries one. */
    val world: CatalogRegion?
        get() = regions.firstOrNull { it.isWorld }

    /** The region entries, in catalogue order. */
    fun entries(): List<CatalogRegion> = regions.filterNot { it.isWorld }

    /**
     * What [id] downloads: a region entry, or the package of one region's
     * add-on (T-0509). Null when the catalogue offers neither.
     */
    fun download(id: String): CatalogDownload? {
        regions.firstOrNull { it.id == id }?.let { return CatalogDownload(it.id, it.version, it.bytes) }
        for (region in regions) {
            val addon = region.addons.firstOrNull { it.kind.isKnownAddon && it.packageId(region.id) == id } ?: continue
            return CatalogDownload(id, addon.version, addon.bytes, addonOf = region.id)
        }
        return null
    }
}

/** One installed region package as the downloads screen reads it. */
@Serializable
data class InstalledPack(
    val region: String,
    val version: String,
    val bytes: Long = 0,
    val parts: List<CatalogPartKind> = emptyList(),
    val dataDate: String? = null,
    /**
     * The monthly tile build of the pack's routing part, or null when the pack
     * has none. Packs of one monthly update share it; two values among the
     * installed packs mean a half-finished update.
     */
    val build: String? = null,
)

/** Why a catalogue or a download did not finish. It carries data, never user text. */
@Serializable
enum class DownloadProblemKind {
    /** A 401 arrived and no token was sent: the server runs with tokens. */
    TOKEN_REQUIRED,

    /** A 401 arrived and a token was sent: the server refused that token. */
    TOKEN_REJECTED,

    /** The privacy switch for region downloads is off. */
    SWITCH_OFF,

    /** No connection, or the connection broke. */
    NETWORK,

    /** The server answered with a status the app does not treat as success. */
    SERVER,

    /** The archive or its manifest is not what it claims to be. */
    CORRUPT_DATA,

    /** This build cannot read the server's package format. */
    UNSUPPORTED,

    /** The store could not write or read the region directory. */
    STORAGE,

    /** An update failed its checks twice. The installed version still works. */
    UPDATE_FAILED,

    /** Anything else, including a defect the app caught at the boundary. */
    OTHER,
}

/** A typed problem, with the HTTP status when one applies. */
@Serializable
data class DownloadProblem(
    val kind: DownloadProblemKind,
    val status: Int = 0,
)

/** One region a problem belongs to. */
@Serializable
data class RegionProblem(
    val region: String,
    val problem: DownloadProblem,
)

/** The stage a transfer is in. */
@Serializable
enum class DownloadTransferState {
    /** Queued behind another region, or waiting for the network. */
    WAITING,

    /**
     * Held back because the network is metered and the policy is
     * [MobileDataPolicy.WIFI_ONLY]. It continues on its own when an unmetered
     * network returns, or at once after the user chooses mobile data for it.
     */
    WAITING_FOR_WIFI,

    /** The archive body is arriving. */
    DOWNLOADING,

    /** The archive arrived and its files are being checked and installed. */
    VERIFYING,
}

/** Whether a download may use a metered network such as mobile data. */
@Serializable
enum class MobileDataPolicy {
    /** A transfer waits while the network is metered. This is the default. */
    WIFI_ONLY,

    /** A transfer runs on any network. */
    ANY,
}

/**
 * One region's transfer. [bytesTotal] is the catalogue's declared size, or 0
 * when the catalogue has none, so the screen cannot divide by it.
 */
@Serializable
data class DownloadTransfer(
    val region: String,
    val state: DownloadTransferState,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
)

/**
 * The data behind the downloads screen: the catalogue, what is on the phone,
 * what is in flight, and the typed problems. The plugin derives every row from
 * it; the store never decides what the screen draws.
 */
@Serializable
data class DownloadsData(
    val catalog: RegionCatalog? = null,
    val catalogProblem: DownloadProblem? = null,
    val installed: List<InstalledPack> = emptyList(),
    val transfers: List<DownloadTransfer> = emptyList(),
    val problems: List<RegionProblem> = emptyList(),
    val serverUrl: String = "",
    val tokenPresent: Boolean = false,
    val mobileDataPolicy: MobileDataPolicy = MobileDataPolicy.WIFI_ONLY,
    /** True while the current network is metered. */
    val networkMetered: Boolean = false,
    /** Whether the monthly background check and update runs (T-0508). On by default. */
    val autoUpdate: Boolean = true,
) {
    /** The transfer for [region], or null when nothing is in flight for it. */
    fun transfer(region: String): DownloadTransfer? = transfers.firstOrNull { it.region == region }

    /** The problem recorded for [region], or null when its last action succeeded. */
    fun problem(region: String): DownloadProblem? = problems.firstOrNull { it.region == region }?.problem

    /** The installed package of [region], or null when it is not on the phone. */
    fun installed(region: String): InstalledPack? = installed.firstOrNull { it.region == region }

    /**
     * The installed regions whose catalogue version differs from the installed
     * one, in catalogue order. A region that is not installed is not an update.
     */
    val updates: List<CatalogRegion>
        get() = catalog?.regions.orEmpty().filter { entry -> installed(entry.id)?.let { it.version != entry.version } == true }

    /** True when the installed packs come from more than one monthly tile build. */
    val hasMixedBuilds: Boolean
        get() = installed.mapNotNull { it.build }.distinct().size > 1
}

/** What one row of the downloads list is doing. */
@Serializable
enum class DownloadRowStatus {
    /** Not on the phone and not in flight. */
    AVAILABLE,

    /** A newer catalogue version than the installed one. */
    UPDATE_AVAILABLE,

    /** Queued or arriving. */
    DOWNLOADING,

    /** On the phone at the catalogue version. */
    INSTALLED,

    /** The last attempt failed; the row carries the problem. */
    FAILED,
}

/** One part of a region, as one row of the region sheet draws it. */
@Serializable
data class DownloadPartRow(
    val kind: CatalogPartKind,
    val version: String,
    val bytes: Long,
    val installed: Boolean,
    /**
     * The id that downloads this part on its own, or null for a part that
     * travels with its region's package. Only an add-on has one (T-0509).
     */
    val downloadId: String? = null,
    /** The transfer of [downloadId], or null when nothing is in flight for it. */
    val transfer: DownloadTransfer? = null,
)

/**
 * One region as the list draws it. [bytes] is the catalogue's declared package
 * size; [parts] carries each part's size so the sheet needs no second lookup.
 */
@Serializable
data class DownloadRow(
    val region: String,
    val nameEn: String? = null,
    val nameUk: String? = null,
    val kind: CatalogRegionKind = CatalogRegionKind.REGION,
    val version: String,
    val dataDate: String? = null,
    val bytes: Long,
    val parts: List<DownloadPartRow> = emptyList(),
    val installedVersion: String? = null,
    val installedBytes: Long? = null,
    val status: DownloadRowStatus = DownloadRowStatus.AVAILABLE,
    val transfer: DownloadTransfer? = null,
    val problem: DownloadProblem? = null,
) {
    /** The name in the user's language, falling back to the other one. */
    fun name(preferUkrainian: Boolean): String? =
        if (preferUkrainian) {
            nameUk ?: nameEn
        } else {
            nameEn ?: nameUk
        }

    val isInstalled: Boolean
        get() = installedVersion != null
}

/** One row of the first-run offer. */
@Serializable
enum class FirstRunOfferKind {
    WORLD,
    REGION,
    ROUTING,
    ELEVATION_30M,
}

/**
 * One thing first run offers to download. [parts] carries the parts the row
 * stands for, so the contours are named with their size inside the region row
 * rather than as a number the screen cannot explain.
 */
@Serializable
data class FirstRunOffer(
    val kind: FirstRunOfferKind,
    val region: String,
    val nameEn: String? = null,
    val nameUk: String? = null,
    val bytes: Long,
    val parts: List<DownloadPartRow> = emptyList(),
    val installed: Boolean = false,
    /** The id the offer's download button sends, when it differs from [region] (an add-on). */
    val downloadId: String? = null,
) {
    fun name(preferUkrainian: Boolean): String? =
        if (preferUkrainian) {
            nameUk ?: nameEn
        } else {
            nameEn ?: nameUk
        }
}

/** The downloads screen as the plugin publishes it. */
@Serializable
data class DownloadsState(
    val serverUrl: String = DownloadsDefaults.SERVER_URL,
    val tokenPresent: Boolean = false,
    val catalogLoaded: Boolean = false,
    val catalogProblem: DownloadProblem? = null,
    /** The region the sheet is open on, or null when it is closed. */
    val selectedRegion: String? = null,
    /** The selected region's row, ready to draw, or null when the sheet is closed. */
    val selected: DownloadRow? = null,
    /** The region the last fix falls in, when there was one. */
    val detectedRegion: String? = null,
    /** Rows on the phone, then rows offered, in catalogue order. */
    val installedRows: List<DownloadRow> = emptyList(),
    val availableRows: List<DownloadRow> = emptyList(),
    /** What first run offers, in the order the screen draws it. */
    val firstRun: List<FirstRunOffer> = emptyList(),
    val mobileDataPolicy: MobileDataPolicy = MobileDataPolicy.WIFI_ONLY,
    /** True while at least one transfer waits for an unmetered network. */
    val waitingForWifi: Boolean = false,
    /** Whether the monthly automatic update is on. */
    val autoUpdate: Boolean = true,
)
