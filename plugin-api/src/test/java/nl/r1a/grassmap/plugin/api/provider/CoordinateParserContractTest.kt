package nl.r1a.grassmap.plugin.api.provider

import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `CoordinateParser` seam (T-0206): a caller that never wires one gets a
 * parser that recognizes nothing, so search behaves as it did before the seam
 * existed. The notations themselves are the implementation's contract and are
 * pinned by the vectors in `plugins/coordinates`.
 */
class CoordinateParserContractTest {
    @Test
    fun theDefaultParserRecognizesNothing() {
        for (text in TEXTS) {
            assertNull("None parsed \"$text\"", CoordinateParser.None.parse(text))
        }
    }

    private companion object {
        val TEXTS =
            listOf(
                "",
                "   ",
                "49.841740, 24.023910",
                "49°49'18.26\"N, 24°01'26.08\"E",
                "N 49 49.3044, E 24 1.4346",
                "львів",
            )
    }
}
