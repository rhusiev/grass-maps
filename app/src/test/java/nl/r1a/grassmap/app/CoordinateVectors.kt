package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The accepted points of the committed coordinate vectors (T-0206).
 *
 * `plugins/coordinates` reads the same file to prove its parser accepts every
 * notation; this side uses the points to prove `plugins/share` writes text the
 * parser reads back. One file, two directions, no copied constants.
 */
internal object CoordinateVectors {
    fun acceptedPoints(): List<GeoPoint> {
        val text = Files.readString(repositoryRoot().resolve(VECTOR_FILE))
        return text
            .lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val columns = line.split('\t')
                if (columns[0] != ACCEPT || columns.size != ACCEPT_COLUMNS) {
                    null
                } else {
                    GeoPoint(latitude = columns[2].toDouble(), longitude = columns[3].toDouble())
                }
            }.toList()
    }

    private fun repositoryRoot(): Path {
        var directory: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("settings.gradle.kts")) &&
                Files.isDirectory(directory.resolve("test-data"))
            ) {
                return directory
            }
            directory = directory.parent
        }
        error("Cannot find the repository root from ${System.getProperty("user.dir")}")
    }

    private const val VECTOR_FILE = "test-data/search/coordinate-notations.tsv"
    private const val ACCEPT = "accept"
    private const val ACCEPT_COLUMNS = 4
}
