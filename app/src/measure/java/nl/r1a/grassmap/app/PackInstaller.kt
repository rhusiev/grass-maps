package nl.r1a.grassmap.app

import android.content.res.AssetManager
import java.io.File
import java.io.IOException

/**
 * Copies the bundled Valhalla pack into the app's external files directory.
 *
 * The measurement build carries a Lviv-sized pack as assets; the routing plugin
 * reads `<externalFilesDir>/valhalla/`, so the first launch writes it there and
 * reports every byte it wrote. A file is copied to a `.part` name and renamed,
 * so an interrupted copy never looks like a complete pack.
 */
internal class PackInstaller(
    private val assets: AssetManager,
    private val externalFiles: File?,
    private val filesDir: File,
    private val log: (String) -> Unit = {},
) {
    /** Installs the pack if it is absent or incomplete and describes the result. */
    fun install(): PackInstall {
        val root = externalFiles ?: filesDir
        val directory = File(root, MeasurementReport.PACK_DIRECTORY)
        val assetNames = assets.list(ASSET_DIRECTORY)?.toList().orEmpty()
        if (assetNames.isEmpty()) {
            return PackInstall(
                directory = directory.absolutePath,
                files = emptyList(),
                bytesWritten = 0,
                copyMillis = 0,
                copied = false,
                problem =
                    "the APK has no $ASSET_DIRECTORY assets; build the measure variant with " +
                        "-PmeasurePackDir pointing at a directory that contains $ASSET_DIRECTORY/",
            )
        }
        if (isComplete(directory)) {
            log("measure-pack-present dir=${directory.absolutePath}")
            return PackInstall(
                directory = directory.absolutePath,
                files = sizes(directory),
                bytesWritten = 0,
                copyMillis = 0,
                copied = false,
                problem = null,
            )
        }
        if (!directory.isDirectory && !directory.mkdirs()) {
            return failed(directory, "cannot create ${directory.absolutePath}")
        }
        val started = System.nanoTime()
        var written = 0L
        for (name in REQUIRED_FILES) {
            val target = File(directory, name)
            val part = File(directory, "$name.part")
            written +=
                try {
                    assets.open("$ASSET_DIRECTORY/$name").use { input ->
                        part.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (part.length() == 0L) {
                        part.delete()
                        return failed(directory, "$ASSET_DIRECTORY/$name is empty")
                    }
                    if (target.exists() && !target.delete()) {
                        part.delete()
                        return failed(directory, "cannot replace ${target.absolutePath}")
                    }
                    if (!part.renameTo(target)) {
                        part.delete()
                        return failed(directory, "cannot rename ${part.absolutePath}")
                    }
                    target.length()
                } catch (exception: IOException) {
                    part.delete()
                    return failed(directory, "cannot copy $ASSET_DIRECTORY/$name: ${exception.message}")
                }
        }
        val elapsed = (System.nanoTime() - started) / NANOS_PER_MILLI
        log("measure-pack-copied dir=${directory.absolutePath} bytes=$written ms=$elapsed")
        return PackInstall(
            directory = directory.absolutePath,
            files = sizes(directory),
            bytesWritten = written,
            copyMillis = elapsed,
            copied = true,
            problem = if (isComplete(directory)) null else "the copy finished but the pack is incomplete",
        )
    }

    private fun isComplete(directory: File): Boolean =
        directory.isDirectory &&
            REQUIRED_FILES.all { name -> File(directory, name).let { it.isFile && it.length() > 0 } }

    private fun sizes(directory: File): List<PackFile> = REQUIRED_FILES.map { name -> PackFile(name, File(directory, name).length()) }

    private fun failed(
        directory: File,
        problem: String,
    ): PackInstall =
        PackInstall(
            directory = directory.absolutePath,
            files = sizes(directory),
            bytesWritten = 0,
            copyMillis = 0,
            copied = false,
            problem = problem,
        )

    companion object {
        const val ASSET_DIRECTORY = "valhalla-lviv"
        private const val NANOS_PER_MILLI = 1_000_000L

        /**
         * The files `ValhallaPack.inspect` requires: the config, an extract,
         * the admin database and the timezone database.
         */
        val REQUIRED_FILES = listOf("valhalla.json", "tiles.tar", "admins.sqlite", "timezones.sqlite")
    }
}
