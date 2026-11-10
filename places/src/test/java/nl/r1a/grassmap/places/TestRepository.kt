package nl.r1a.grassmap.places

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The repository root, found by walking up from the test working directory.
 *
 * A test that reads a file shared between modules (the normalisation vectors,
 * the committed region package) uses this instead of a copy in `resources`, so
 * the two sides read the same bytes.
 */
internal fun repositoryRoot(): Path {
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
