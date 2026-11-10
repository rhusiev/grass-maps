package nl.r1a.grassmap.places

import nl.r1a.grassmap.places.core.canonicalWords
import nl.r1a.grassmap.places.core.normaliseText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The shared normalisation vectors, run through the app's own bindings
 * (T-0205).
 *
 * `test-data/search/normalisation-vectors.tsv` is read, not copied and not
 * restated: the crate's `fold::tests` reads the same file. The builder folds a
 * name with the crate and the query folds the typed text with the library the
 * app ships, and a change that moves only one of them fails one of the two
 * tests. This side also covers the generated bindings, which the crate's test
 * cannot.
 */
class NormalisationVectorsTest {
    @Test
    fun `every shared vector holds through the app bindings`() {
        val rows = readVectors()
        assertTrue("the vector file lost rows: ${rows.size}", rows.size >= 20)
        for (row in rows) {
            val actual =
                when (row.function) {
                    "fold" -> normaliseText(row.input)
                    "words" -> canonicalWords(row.input).joinToString(" ")
                    else -> throw AssertionError("unknown vector function ${row.function}")
                }
            assertEquals("${row.function}(${row.input})", row.expected, actual)
        }
    }

    private data class Vector(
        val function: String,
        val input: String,
        val expected: String,
    )

    private fun readVectors(): List<Vector> {
        val path = repositoryRoot().resolve(VECTOR_FILE)
        val text = Files.readString(path)
        return text
            .lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val columns = line.split('\t')
                assertEquals("malformed vector row: $line", COLUMN_COUNT, columns.size)
                Vector(function = columns[0], input = columns[1], expected = columns[2])
            }.toList()
    }

    companion object {
        private const val VECTOR_FILE = "test-data/search/normalisation-vectors.tsv"
        private const val COLUMN_COUNT = 3
    }
}
