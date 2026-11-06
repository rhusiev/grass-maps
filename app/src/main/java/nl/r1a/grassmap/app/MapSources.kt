package nl.r1a.grassmap.app

import android.content.Context
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.MapSourceIds
import nl.r1a.grassmap.plugin.api.model.SourceId
import java.io.File

/**
 * Resolves the logical map sources to local PMTiles archives. The Lviv bench
 * archive wins when it is pushed to the app; otherwise the small T-0015 fixture
 * is copied out of the debug assets. `world` has no archive on this machine, so
 * its low-zoom layers are skipped.
 */
internal class MapSources private constructor(
    private val uris: Map<SourceId, String>,
    val camera: Camera,
    val regionName: String,
    val hasContours: Boolean,
) {
    fun uri(id: SourceId): String? = uris[id]

    companion object {
        const val LVIV_ARCHIVE = "lviv-protomaps.pmtiles"
        const val CONTOURS_ARCHIVE = "lviv-contours-20m.pmtiles"
        private const val FIXTURE_ASSET = "fixture-mini-map.pmtiles"
        private const val FIXTURE_FILE = "fixture-mini-map.pmtiles"

        fun resolve(context: Context): MapSources {
            val directories = listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
            val lviv = directories.map { File(it, LVIV_ARCHIVE) }.firstOrNull { it.isUsable() }
            val contours = directories.map { File(it, CONTOURS_ARCHIVE) }.firstOrNull { it.isUsable() }
            val region = lviv ?: copyFixture(context)
            val uris =
                buildMap {
                    region?.let { put(MapSourceIds.REGION, pmtilesUri(it)) }
                    contours?.let { put(MapSourceIds.CONTOURS, pmtilesUri(it)) }
                }
            val camera =
                if (lviv != null) {
                    Camera(center = GeoPoint(49.8419, 24.0316), zoom = 12.5)
                } else {
                    Camera(center = GeoPoint(49.9500, 23.9450), zoom = 13.0)
                }
            return MapSources(
                uris = uris,
                camera = camera,
                regionName = if (lviv != null) LVIV_ARCHIVE else FIXTURE_FILE,
                hasContours = contours != null,
            )
        }

        private fun copyFixture(context: Context): File? {
            val target = File(context.filesDir, FIXTURE_FILE)
            if (target.isUsable()) {
                return target
            }
            return runCatching {
                val partial = File(context.filesDir, "$FIXTURE_FILE.part")
                context.assets.open(FIXTURE_ASSET).use { input ->
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
