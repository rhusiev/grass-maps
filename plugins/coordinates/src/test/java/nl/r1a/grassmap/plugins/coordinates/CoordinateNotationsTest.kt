package nl.r1a.grassmap.plugins.coordinates

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The committed coordinate vectors (T-0206).
 *
 * `test-data/search/coordinate-notations.tsv` is read, not copied and not
 * restated: the same file feeds the app's format-and-parse round trip, so a
 * notation added here is exercised in both directions. Every accept row must
 * parse to its point and every refuse row must stay null; a miss prints the
 * text and what came back instead.
 */
class CoordinateNotationsTest {
    @Test
    fun `every accepted notation parses to its point`() {
        val rows = readVectors()
        val accepted = rows.filter { it.expected != null }
        assertTrue("the vector file lost its notations: ${accepted.size}", accepted.size >= MIN_NOTATIONS)
        val misses = mutableListOf<String>()
        for (row in accepted) {
            val actual = Coordinates.parse(row.text)
            val expected = row.expected ?: continue
            if (actual == null || !row.matches(actual)) {
                misses += "${row.text} -> $actual, expected $expected"
            }
        }
        assertEquals("accept misses: ${misses.joinToString("; ")}", emptyList<String>(), misses)
    }

    @Test
    fun `every refused near miss stays refused`() {
        val rows = readVectors()
        val refused = rows.filter { it.reason != null }
        assertTrue("the vector file lost its near misses: ${refused.size}", refused.size >= MIN_REFUSALS)
        val accepted = mutableListOf<String>()
        for (row in refused) {
            val actual = Coordinates.parse(row.text)
            if (actual != null) {
                accepted += "${row.text} parsed as $actual but must be refused: ${row.reason}"
            }
        }
        assertEquals("refuse misses: ${accepted.joinToString("; ")}", emptyList<String>(), accepted)
    }

    @Test
    fun `negative zero keeps its sign`() {
        val point = Coordinates.parse("-0.0, -0.0")
        assertNotNull("a signed zero pair must parse", point)
        assertEquals("-0.0 latitude", (-0.0).toRawBits(), (point?.latitude ?: 0.0).toRawBits())
        assertEquals("-0.0 longitude", (-0.0).toRawBits(), (point?.longitude ?: 0.0).toRawBits())
    }

    @Test
    fun `the axis ends are accepted and one step past them is not`() {
        assertEquals(GeoPoint(90.0, 180.0), Coordinates.parse("90.0, 180.0"))
        assertEquals(GeoPoint(-90.0, -180.0), Coordinates.parse("-90.0, -180.0"))
        assertNull(Coordinates.parse("90.0001, 0.0"))
        assertNull(Coordinates.parse("-90.0001, 0.0"))
        assertNull(Coordinates.parse("0.0, 180.0001"))
    }

    @Test
    fun `an ordinary query is left to the index`() {
        for (text in listOf("львів", "аптека", "вулиця 12", "24/7", "Mедовий двір", "N")) {
            assertNull("\"$text\" must not become a coordinate", Coordinates.parse(text))
        }
    }

    private data class Vector(
        val text: String,
        val expected: GeoPoint?,
        val reason: String?,
    ) {
        fun matches(point: GeoPoint): Boolean =
            kotlin.math.abs(point.latitude - (expected?.latitude ?: Double.NaN)) <= TOLERANCE &&
                kotlin.math.abs(point.longitude - (expected?.longitude ?: Double.NaN)) <= TOLERANCE
    }

    private fun readVectors(): List<Vector> {
        val text = Files.readString(repositoryRoot().resolve(VECTOR_FILE))
        return text
            .lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val columns = line.split('\t')
                when (columns[0]) {
                    ACCEPT -> {
                        assertEquals("malformed accept row: $line", ACCEPT_COLUMNS, columns.size)
                        Vector(
                            text = columns[1],
                            expected = GeoPoint(columns[2].toDouble(), columns[3].toDouble()),
                            reason = null,
                        )
                    }

                    REFUSE -> {
                        assertEquals("malformed refuse row: $line", REFUSE_COLUMNS, columns.size)
                        Vector(text = columns[1], expected = null, reason = columns[2])
                    }

                    else -> {
                        throw AssertionError("unknown vector kind in: $line")
                    }
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

    private companion object {
        const val VECTOR_FILE = "test-data/search/coordinate-notations.tsv"
        const val ACCEPT = "accept"
        const val REFUSE = "refuse"
        const val ACCEPT_COLUMNS = 4
        const val REFUSE_COLUMNS = 3
        const val MIN_NOTATIONS = 12
        const val MIN_REFUSALS = 6
        const val TOLERANCE = 1e-9
    }
}
