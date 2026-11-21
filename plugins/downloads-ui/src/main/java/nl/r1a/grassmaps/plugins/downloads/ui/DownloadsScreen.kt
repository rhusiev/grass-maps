package nl.r1a.grassmaps.plugins.downloads.ui

import nl.r1a.grassmaps.plugin.api.model.CatalogAddon
import nl.r1a.grassmaps.plugin.api.model.CatalogPart
import nl.r1a.grassmaps.plugin.api.model.CatalogPartKind
import nl.r1a.grassmaps.plugin.api.model.CatalogRegion
import nl.r1a.grassmaps.plugin.api.model.DownloadPartRow
import nl.r1a.grassmaps.plugin.api.model.DownloadRow
import nl.r1a.grassmaps.plugin.api.model.DownloadRowStatus
import nl.r1a.grassmaps.plugin.api.model.DownloadTransferState
import nl.r1a.grassmaps.plugin.api.model.DownloadsData
import nl.r1a.grassmaps.plugin.api.model.DownloadsDefaults
import nl.r1a.grassmaps.plugin.api.model.DownloadsState
import nl.r1a.grassmaps.plugin.api.model.FirstRunOffer
import nl.r1a.grassmaps.plugin.api.model.FirstRunOfferKind
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.InstalledPack
import nl.r1a.grassmaps.plugin.api.model.RegionCatalog
import java.util.Locale

/**
 * The downloads screen as a pure derivation of the store's data (T-0504).
 *
 * The plugin owns this and the store owns nothing of it: rows, the two list
 * groups, the region sheet's parts and the first-run offer are all computed here
 * from one [DownloadsData], so a test can state the screen exactly and the
 * Compose content has no facts of its own.
 *
 * A region is one archive, so the sheet's parts are what the archive contains,
 * never separate downloads. The 30 m elevation row is the catalogue's add-on
 * (server-protocol.md section 2) and is drawn only when the server offers one.
 */
object DownloadsScreen {
    /** The parts a first-run region row stands for: base, search, elevation and contours. */
    private val FIRST_RUN_REGION_KINDS =
        setOf(
            CatalogPartKind.MAP,
            CatalogPartKind.SEARCH,
            CatalogPartKind.ELEVATION,
            CatalogPartKind.CONTOURS,
        )

    private val APOSTROPHES = Regex("[\u0027\u2019\u02BC\u2018`]")
    private val WHITESPACE = Regex("\\s+")

    /**
     * The whole screen for [data]. [selectedRegion] is the sheet the user opened
     * and [detectedRegion] the oblast the last fix fell in; both are matched
     * against the catalogue, so a stale id draws nothing instead of a blank row.
     */
    fun state(
        data: DownloadsData,
        selectedRegion: String? = null,
        detectedRegion: String? = null,
        query: String = "",
    ): DownloadsState {
        val catalog = data.catalog
        val rows = catalog?.regions.orEmpty().map { entry -> row(data, entry) }
        val selected = selectedRegion?.let { id -> rows.firstOrNull { it.region == id } }
        val detected = detectedRegion?.takeIf { id -> rows.any { it.region == id } }
        val firstRun = firstRun(data, detected)
        // While the first-run offer is on screen it already names these regions,
        // so the list below it does not repeat them.
        val offered =
            if (firstRun.isNotEmpty() && rows.none { it.isInstalled }) {
                firstRun.map { it.region }.toSet()
            } else {
                emptySet()
            }
        return DownloadsState(
            serverUrl = data.serverUrl.ifBlank { DownloadsDefaults.SERVER_URL },
            tokenPresent = data.tokenPresent,
            catalogLoaded = catalog != null,
            catalogProblem = data.catalogProblem,
            selectedRegion = selected?.region,
            selected = selected,
            detectedRegion = detected,
            installedRows = rows.filter { it.isInstalled },
            availableRows = rows.filterNot { it.isInstalled }.filterNot { it.region in offered },
            firstRun = firstRun,
            mobileDataPolicy = data.mobileDataPolicy,
            waitingForWifi = data.transfers.any { it.state == DownloadTransferState.WAITING_FOR_WIFI },
            autoUpdate = data.autoUpdate,
            appliesAtNextStart = data.appliesAtNextStart,
            outlines = data.outlines,
            outlineStatuses = rows.filter { row -> data.outlines.any { it.region == row.region } }.associate { it.region to it.status },
            query = query,
            searchResults = search(rows, query),
            nearMe = nearMe(rows, detected, offered),
        )
    }

    /**
     * The detected oblast as a row, while it is not installed. Without a
     * detected oblast, or while the first-run offer already names it, there is
     * nothing to suggest.
     */
    private fun nearMe(
        rows: List<DownloadRow>,
        detected: String?,
        offered: Set<String>,
    ): DownloadRow? =
        rows.firstOrNull { it.region == detected && it.region !in offered }?.takeIf { it.status != DownloadRowStatus.INSTALLED }

    /**
     * The rows whose Ukrainian name, English name or id contains [query], in
     * catalogue order. The names are the catalogue's own, so "Ternopil" and
     * "Тернопіль" both find "Ternopil Oblast" and "Тернопільська область".
     * Case, surrounding space and the apostrophe variants are ignored. A blank
     * query matches nothing, because the screen then draws its plain lists.
     */
    fun search(
        rows: List<DownloadRow>,
        query: String,
    ): List<DownloadRow> {
        val wanted = fold(query)
        if (wanted.isEmpty()) {
            return emptyList()
        }
        return rows.filter { row ->
            listOfNotNull(row.nameEn, row.nameUk, row.region).any { name -> fold(name).contains(wanted) }
        }
    }

    private fun fold(text: String): String =
        text
            .lowercase(Locale.ROOT)
            .replace(APOSTROPHES, "")
            .trim()
            .replace(WHITESPACE, " ")

    /**
     * The region whose declared bbox contains [fix], or null when no region does.
     * The world entry is not a candidate: its box covers everything and says
     * nothing about where the phone is.
     */
    fun detectRegion(
        catalog: RegionCatalog?,
        fix: GeoPoint?,
    ): String? {
        if (catalog == null || fix == null) {
            return null
        }
        return catalog.entries().firstOrNull { region -> region.bounds?.contains(fix) == true }?.id
    }

    /**
     * What first run offers: the world overview, then the detected oblast's
     * region row with its contours, its routing and its 30 m elevation, each as a
     * part with its own size. Without a detected region only the world is
     * offered, and the list is the way in.
     */
    fun firstRun(
        data: DownloadsData,
        detectedRegion: String?,
    ): List<FirstRunOffer> {
        val catalog = data.catalog ?: return emptyList()
        val offers = mutableListOf<FirstRunOffer>()
        catalog.world?.let { world ->
            val installed = data.installed(world.id)
            offers +=
                FirstRunOffer(
                    kind = FirstRunOfferKind.WORLD,
                    region = world.id,
                    nameEn = world.nameEn,
                    nameUk = world.nameUk,
                    bytes = world.bytes,
                    parts = partRows(data, world, installed),
                    installed = installed?.version == world.version,
                )
        }
        val detected = detectedRegion?.let { id -> catalog.regions.firstOrNull { it.id == id } } ?: return offers
        val installed = data.installed(detected.id)
        val installedParts = installed?.parts.orEmpty()
        val atVersion = installed != null && installed.version == detected.version

        val base = detected.parts.filter { it.kind in FIRST_RUN_REGION_KINDS }
        if (base.isNotEmpty()) {
            offers +=
                FirstRunOffer(
                    kind = FirstRunOfferKind.REGION,
                    region = detected.id,
                    nameEn = detected.nameEn,
                    nameUk = detected.nameUk,
                    bytes = base.sumOf { it.bytes },
                    parts = base.map { part -> partRow(part, installedParts) },
                    installed = atVersion && base.all { installedParts.contains(it.kind) },
                )
        }
        detected.parts.firstOrNull { it.kind == CatalogPartKind.ROUTING }?.let { routing ->
            offers +=
                FirstRunOffer(
                    kind = FirstRunOfferKind.ROUTING,
                    region = detected.id,
                    nameEn = detected.nameEn,
                    nameUk = detected.nameUk,
                    bytes = routing.bytes,
                    parts = listOf(partRow(routing, installedParts)),
                    installed = atVersion && installedParts.contains(CatalogPartKind.ROUTING),
                )
        }
        detected.addons.firstOrNull { it.kind == CatalogPartKind.ELEVATION_30M }?.takeIf { installed != null }?.let { addon ->
            offers +=
                FirstRunOffer(
                    kind = FirstRunOfferKind.ELEVATION_30M,
                    region = detected.id,
                    nameEn = detected.nameEn,
                    nameUk = detected.nameUk,
                    bytes = addon.bytes,
                    parts = listOf(addonRow(data, detected, addon, installedParts)),
                    installed = installedParts.contains(CatalogPartKind.ELEVATION_30M),
                    downloadId = addon.packageId(detected.id),
                )
        }
        return offers
    }

    /** One catalogue entry as a list row, with the parts the archive carries. */
    fun row(
        data: DownloadsData,
        entry: CatalogRegion,
    ): DownloadRow {
        val installed = data.installed(entry.id)
        val transfer = data.transfer(entry.id)
        val problem = data.problem(entry.id)
        val status =
            when {
                transfer != null -> DownloadRowStatus.DOWNLOADING
                problem != null -> DownloadRowStatus.FAILED
                installed == null -> DownloadRowStatus.AVAILABLE
                installed.version == entry.version -> DownloadRowStatus.INSTALLED
                else -> DownloadRowStatus.UPDATE_AVAILABLE
            }
        return DownloadRow(
            region = entry.id,
            nameEn = entry.nameEn,
            nameUk = entry.nameUk,
            kind = entry.kind,
            version = entry.version,
            // An installed row shows the date of the data on the phone; an
            // offered row shows the date the download would bring.
            dataDate = installed?.dataDate ?: entry.dataDate,
            bytes = entry.bytes,
            parts = partRows(data, entry, installed),
            installedVersion = installed?.version,
            installedBytes = installed?.bytes,
            status = status,
            transfer = transfer,
            problem = problem,
        )
    }

    private fun partRows(
        data: DownloadsData,
        entry: CatalogRegion,
        installed: InstalledPack?,
    ): List<DownloadPartRow> {
        val installedParts = installed?.parts.orEmpty()
        return entry.parts.map { part -> partRow(part, installedParts) } +
            entry.addons.map { addon -> addonRow(data, entry, addon, installedParts) }
    }

    /** An add-on is a package of its own, so its row carries the id and transfer of that package. */
    private fun addonRow(
        data: DownloadsData,
        entry: CatalogRegion,
        addon: CatalogAddon,
        installedParts: List<CatalogPartKind>,
    ): DownloadPartRow {
        val id = addon.packageId(entry.id)
        return DownloadPartRow(
            kind = addon.kind,
            version = addon.version,
            bytes = addon.bytes,
            installed = installedParts.contains(addon.kind),
            downloadId = id,
            transfer = data.transfer(id),
        )
    }

    private fun partRow(
        part: CatalogPart,
        installedParts: List<CatalogPartKind>,
    ): DownloadPartRow =
        DownloadPartRow(
            kind = part.kind,
            version = part.version,
            bytes = part.bytes,
            installed = installedParts.contains(part.kind),
        )
}
