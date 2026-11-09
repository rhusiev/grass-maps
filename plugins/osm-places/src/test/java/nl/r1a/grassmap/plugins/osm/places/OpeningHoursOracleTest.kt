package nl.r1a.grassmap.plugins.osm.places

import nl.r1a.grassmap.plugin.api.model.PlaceOpeningState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * Parity with `opening_hours.js` on 500 Lviv values at ten instants (T-0209).
 *
 * The oracle is committed under `test-data/places/opening-hours/`; npm is not
 * needed at build or CI time. Every value's ten states must equal the oracle,
 * and the six values the evaluator cannot parse must be exactly the six the
 * oracle also reports as unknown everywhere.
 */
class OpeningHoursOracleTest {
    @Test
    fun fiveHundredLvivValuesAgreeAtTenInstants() {
        val rows = oracleRows()
        val byValue = rows.groupBy { it.value }
        val mismatches = mutableListOf<String>()
        val unsupported = mutableListOf<String>()
        var matchedValues = 0
        var unknownInstants = 0

        for ((value, valueRows) in byValue) {
            val parsed = OpeningHoursValue.parse(value)
            if (parsed == null) {
                unsupported += value
            }
            var valueMatched = true
            for (row in valueRows) {
                val at = row.instant.atZone(OpeningHoursValue.KYIV)
                val actual = parsed?.stateAt(at) ?: PlaceOpeningState.UNKNOWN
                if (actual == PlaceOpeningState.UNKNOWN) {
                    unknownInstants += 1
                }
                if (actual != row.expected) {
                    valueMatched = false
                    mismatches += "$value @ ${row.instant} expected ${row.expected} but got $actual"
                }
            }
            if (valueMatched) {
                matchedValues += 1
            }
        }

        assertEquals("values in the oracle", 500, byValue.size)
        assertEquals("rows in the oracle", 5000, rows.size)
        assertEquals("differences:\n${mismatches.take(20).joinToString("\n")}", 0, mismatches.size)
        assertEquals("matched values", 500, matchedValues)
        assertEquals(
            "unsupported values:\n${unsupported.joinToString("\n")}",
            6,
            unsupported.size,
        )
        assertEquals("instants the evaluator reports as unknown", 60, unknownInstants)
        assertEquals("oracle-unknown instants", 60, rows.count { it.expected == PlaceOpeningState.UNKNOWN })
    }

    private fun oracleRows(): List<OracleRow> {
        val stream = checkNotNull(javaClass.getResourceAsStream(ORACLE)) { "missing $ORACLE" }
        return stream.bufferedReader().useLines { lines ->
            lines
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { line ->
                    val columns = line.split('\t')
                    check(columns.size == COLUMN_COUNT) { "bad oracle row: $line" }
                    OracleRow(
                        value = columns[4],
                        instant = Instant.parse(columns[5]),
                        expected = state(columns[6], line),
                    )
                }.toList()
        }
    }

    private fun state(
        text: String,
        row: String,
    ): PlaceOpeningState =
        when (text) {
            "open" -> PlaceOpeningState.OPEN
            "closed" -> PlaceOpeningState.CLOSED
            "unknown" -> PlaceOpeningState.UNKNOWN
            else -> error("bad state '$text' in $row")
        }

    private data class OracleRow(
        val value: String,
        val instant: Instant,
        val expected: PlaceOpeningState,
    )

    private companion object {
        const val ORACLE = "/places/opening-hours/lviv-oracle.tsv"
        const val COLUMN_COUNT = 7
    }
}
