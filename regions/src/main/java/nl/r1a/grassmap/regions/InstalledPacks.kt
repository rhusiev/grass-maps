package nl.r1a.grassmap.regions

import nl.r1a.grassmap.plugin.api.model.CatalogPartKind
import nl.r1a.grassmap.plugin.api.model.InstalledPack
import java.io.File

/** Describes mounted regions as the downloads screen's installed packs. */
object InstalledPacks {
    /**
     * The packs for [mounted], in region-id order, with each region's 30 m
     * elevation add-on as it is on disk now. Reads headers and file sizes only.
     */
    fun of(
        root: File,
        mounted: List<MountedRegion>,
    ): List<InstalledPack> {
        val resolver = RegionMountResolver(root)
        return mounted.sortedBy { it.region }.map { region ->
            val addon = resolver.elevationAddon(region.region)
            InstalledPack(
                region = region.region,
                version = region.version,
                bytes = region.manifest.totalBytes + (addon?.let { addonBytes(it) } ?: 0L),
                parts =
                    (
                        region.manifest.components.map { CatalogPartKind.fromWire(it.kind) } +
                            listOfNotNull(CatalogPartKind.ELEVATION_30M.takeIf { addon != null })
                    ).filter { it != CatalogPartKind.OTHER }.distinct(),
                dataDate = RegionCatalogParser.dataDateOf(region.version),
                build = (region.routing as? RoutingPart.Directory)?.build,
            )
        }
    }

    /** The size of the add-on's one component, read from the file the resolver checked. */
    private fun addonBytes(directory: File): Long =
        directory.listFiles()?.filter { it.isFile && it.name != RegionIdentifiers.MANIFEST_FILE }?.sumOf { it.length() } ?: 0L
}
