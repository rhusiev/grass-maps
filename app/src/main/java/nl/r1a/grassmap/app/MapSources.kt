package nl.r1a.grassmap.app

import android.content.Context
import nl.r1a.grassmap.map.MapTileSource
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.MapSourceIds
import nl.r1a.grassmap.plugin.api.model.SourceId
import nl.r1a.grassmap.regions.RegionIdentifiers
import nl.r1a.grassmap.regions.RegionMountProblem
import nl.r1a.grassmap.regions.RegionMountResolver
import nl.r1a.grassmap.regions.RegionMounts
import nl.r1a.grassmap.regions.RegionTileSources
import nl.r1a.grassmap.regions.TileKind
import java.io.File

/**
 * Resolves the logical map sources of T-0020, in this order:
 *
 * 1. every installed and mounted region package in the app's region store, as
 *    one logical source per component kind: `world` and `region` read the
 *    `map` components of all packs, `contours` their `contours` components;
 * 2. the whole-oblast archive pushed to the app's files directory;
 * 3. the small T-0015 fixture (`fixture-mini-map.pmtiles`) copied out of the
 *    debug assets, with its own `fixture-mini-contours.pmtiles` component.
 *
 * A shared border tile is present in both neighbouring packs, byte for byte.
 * Reading them as one source per kind means the first pack in region-id order
 * serves it and the second copy is never requested, so its labels draw once.
 * `world` reads the same `map` components as `region`. An oblast pack carries
 * no z0-z7 tiles, so its low-zoom layers stay empty until the world overview
 * pack is installed. The overview carries no z8+ tiles, so with it alone the
 * map opens on [WORLD_CAMERA] instead of an oblast.
 *
 * An installed region that fails to mount is not silently ignored: the map
 * still opens on the next source, and [regionProblem] carries the typed reason
 * so the screen can show "needs re-download".
 */
internal class MapSources private constructor(
    private val uris: Map<SourceId, String>,
    val tileSources: Map<SourceId, MapTileSource>,
    val camera: Camera,
    val regionName: String,
    val hasContours: Boolean,
    val regionProblem: RegionMountProblem?,
    val problemRegion: String?,
) {
    fun uri(id: SourceId): String? = uris[id]

    companion object {
        const val LVIV_ARCHIVE = "lviv-protomaps.pmtiles"
        const val CONTOURS_ARCHIVE = "lviv-contours-10m.pmtiles"
        private const val FIXTURE_ASSET = "fixture-mini-map.pmtiles"
        private const val FIXTURE_FILE = "fixture-mini-map.pmtiles"
        private const val FIXTURE_CONTOURS_ASSET = "fixture-mini-contours.pmtiles"
        private const val FIXTURE_CONTOURS_FILE = "fixture-mini-contours.pmtiles"
        private const val NO_SOURCE = "none"

        private val LVIV_CAMERA = Camera(center = GeoPoint(49.8419, 24.0316), zoom = 12.5)

        /** Chosen, not derived: the overview alone draws only up to z7. */
        internal val WORLD_CAMERA = Camera(center = GeoPoint(49.0, 31.0), zoom = 4.0)
        private val FIXTURE_CAMERA = Camera(center = GeoPoint(49.9500, 23.9450), zoom = 13.0)

        fun resolve(
            context: Context,
            regionsRoot: File?,
        ): MapSources =
            resolve(
                regionsRoot = regionsRoot,
                directories = listOfNotNull(context.getExternalFilesDir(null), context.filesDir),
                fixture = { copyAsset(context, FIXTURE_ASSET, FIXTURE_FILE) },
                contoursFixture = { copyAsset(context, FIXTURE_CONTOURS_ASSET, FIXTURE_CONTOURS_FILE) },
            )

        /**
         * The testable form of [resolve]. [mount] is the read-only region mount;
         * a caller that has no region store passes no root.
         */
        internal fun resolve(
            regionsRoot: File?,
            directories: List<File>,
            fixture: () -> File?,
            contoursFixture: () -> File? = { null },
            mount: (File) -> RegionMounts = { root -> RegionMountResolver(root).resolveAll() },
        ): MapSources {
            val mounts = regionsRoot?.let(mount)
            val problem = mounts?.broken?.firstOrNull()
            val tiles = mounts?.let { RegionTileSources.open(it.installed) } ?: RegionTileSources.empty()
            if (tiles.has(TileKind.MAP)) {
                return MapSources(
                    uris = emptyMap(),
                    tileSources = tileSourcesOf(tiles),
                    camera = if (isWorldOnly(tiles)) WORLD_CAMERA else LVIV_CAMERA,
                    regionName = packName(tiles),
                    hasContours = tiles.has(TileKind.CONTOURS),
                    // A broken pack is reported even when the others mounted, so
                    // its "needs re-download" banner is never lost.
                    regionProblem = problem?.problem,
                    problemRegion = problem?.region,
                )
            }
            val lviv = directories.map { File(it, LVIV_ARCHIVE) }.firstOrNull { it.isUsable() }
            val pushedContours = directories.map { File(it, CONTOURS_ARCHIVE) }.firstOrNull { it.isUsable() }
            val region = lviv ?: fixture()
            val contours = pushedContours ?: if (lviv == null) contoursFixture() else null
            val uris =
                buildMap {
                    region?.let { put(MapSourceIds.REGION, pmtilesUri(it)) }
                    contours?.let { put(MapSourceIds.CONTOURS, pmtilesUri(it)) }
                }
            val name =
                when {
                    lviv != null -> LVIV_ARCHIVE
                    region != null -> FIXTURE_FILE
                    else -> NO_SOURCE
                }
            return MapSources(
                uris = uris,
                tileSources = emptyMap(),
                camera = if (lviv != null) LVIV_CAMERA else FIXTURE_CAMERA,
                regionName = name,
                hasContours = contours != null,
                regionProblem = problem?.problem,
                problemRegion = problem?.region,
            )
        }

        /**
         * The one logical source per kind. `world` and `region` are two source
         * ids with two zoom bands over the same `map` components, so they share
         * one reader and no tile is served twice.
         */
        private fun tileSourcesOf(tiles: RegionTileSources): Map<SourceId, MapTileSource> {
            val map =
                MapTileSource { zoom, x, y ->
                    tiles.tile(TileKind.MAP, zoom, x, y)?.bytes
                }
            val sources = linkedMapOf(MapSourceIds.WORLD to map, MapSourceIds.REGION to map)
            if (tiles.has(TileKind.CONTOURS)) {
                sources[MapSourceIds.CONTOURS] =
                    MapTileSource { zoom, x, y ->
                        tiles.tile(TileKind.CONTOURS, zoom, x, y)?.bytes
                    }
            }
            return sources
        }

        private fun isWorldOnly(tiles: RegionTileSources): Boolean = tiles.regions.all { it == RegionIdentifiers.WORLD_REGION }

        /** `region:<id>/<version>` for one pack, `regions:<id>,<id>` for several. */
        private fun packName(tiles: RegionTileSources): String =
            tiles.packs.singleOrNull()?.let { "region:${it.region}/${it.version}" }
                ?: "regions:${tiles.regions.joinToString(",")}"

        private fun copyAsset(
            context: Context,
            asset: String,
            name: String,
        ): File? {
            val target = File(context.filesDir, name)
            if (target.isUsable()) {
                return target
            }
            return runCatching {
                val partial = File(context.filesDir, "$name.part")
                context.assets.open(asset).use { input ->
                    partial.outputStream().use { output -> input.copyTo(output) }
                }
                check(partial.renameTo(target)) { "Cannot move $partial to $target" }
                target
            }.getOrNull()
        }

        private fun pmtilesUri(file: File): String = "pmtiles://file://${file.absolutePath}"

        private fun File.isUsable(): Boolean = isFile && length() > 0
    }
}
