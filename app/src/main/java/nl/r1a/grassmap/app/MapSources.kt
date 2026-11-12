package nl.r1a.grassmap.app

import android.content.Context
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.MapSourceIds
import nl.r1a.grassmap.plugin.api.model.SourceId
import nl.r1a.grassmap.regions.RegionMount
import nl.r1a.grassmap.regions.RegionMountProblem
import nl.r1a.grassmap.regions.RegionMountResolver
import java.io.File

/**
 * Resolves the logical map sources to local PMTiles archives, in this order:
 *
 * 1. exactly one installed region package whose pointer, manifest and
 *    map/contours files validate, from the app's region store;
 * 2. the whole-oblast archive pushed to the app's files directory;
 * 3. the small T-0015 fixture (`fixture-mini-map.pmtiles`) copied out of the
 *    debug assets, with its own `fixture-mini-contours.pmtiles` component.
 *
 * An installed region that fails to mount is not silently ignored: the map
 * still opens on the next source, and [regionProblem] carries the typed reason
 * so the screen can show "needs re-download". `world` has no archive on this
 * machine, so its low-zoom layers are skipped.
 */
internal class MapSources private constructor(
    private val uris: Map<SourceId, String>,
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
            mount: (File) -> RegionMount = { root -> RegionMountResolver(root).resolve() },
        ): MapSources {
            val mountResult = regionsRoot?.let(mount) ?: RegionMount.None
            val mounted = (mountResult as? RegionMount.Mounted)?.region
            val invalid = mountResult as? RegionMount.Invalid
            val problem = invalid?.problem
            if (mounted != null) {
                val uris =
                    buildMap {
                        put(MapSourceIds.REGION, pmtilesUri(mounted.map))
                        mounted.contours?.let { put(MapSourceIds.CONTOURS, pmtilesUri(it)) }
                    }
                return MapSources(
                    uris = uris,
                    camera = LVIV_CAMERA,
                    regionName = "region:${mounted.region}/${mounted.version}",
                    hasContours = mounted.contours != null,
                    regionProblem = null,
                    problemRegion = null,
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
                camera = if (lviv != null) LVIV_CAMERA else FIXTURE_CAMERA,
                regionName = name,
                hasContours = contours != null,
                regionProblem = problem,
                problemRegion = invalid?.region,
            )
        }

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
