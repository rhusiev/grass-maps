package nl.r1a.grassmap.plugins.share

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.PlaceCardValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three coordinate notations (T-0210), pinned at several hemispheres and
 * at the exact degree and second boundaries where rounding can print `60`.
 */
class CoordinateFormatsTest {
    @Test
    fun decimalKeepsSixPlacesAndTheSign() {
        assertEquals("49.821740, 24.023910", CoordinateFormats.decimal(GeoPoint(49.821740, 24.023910)))
        assertEquals("-33.868820, 151.209290", CoordinateFormats.decimal(GeoPoint(-33.868820, 151.209290)))
        assertEquals("40.712800, -74.006000", CoordinateFormats.decimal(GeoPoint(40.7128, -74.0060)))
    }

    @Test
    fun dmsPrintsWholeDegreesMinutesAndTwoDecimalSeconds() {
        assertEquals(
            "49°49'18.26\"N, 24°01'26.08\"E",
            CoordinateFormats.dms(GeoPoint(49.821740, 24.023910)),
        )
        assertEquals(
            "33°52'07.75\"S, 151°12'33.44\"E",
            CoordinateFormats.dms(GeoPoint(-33.868820, 151.209290)),
        )
        assertEquals(
            "40°42'46.08\"N, 74°00'21.60\"W",
            CoordinateFormats.dms(GeoPoint(40.7128, -74.0060)),
        )
    }

    @Test
    fun ddmPrintsWholeDegreesAndFourDecimalMinutes() {
        assertEquals(
            "49°49.3044'N, 24°01.4346'E",
            CoordinateFormats.ddm(GeoPoint(49.821740, 24.023910)),
        )
        assertEquals(
            "33°52.1292'S, 151°12.5574'E",
            CoordinateFormats.ddm(GeoPoint(-33.868820, 151.209290)),
        )
        assertEquals(
            "40°42.7680'N, 74°00.3600'W",
            CoordinateFormats.ddm(GeoPoint(40.7128, -74.0060)),
        )
    }

    @Test
    fun roundingCarriesIntoTheNextMinuteAndDegree() {
        val almostFifty = GeoPoint(49.9999999, 179.9999999)

        assertEquals("50.000000, 180.000000", CoordinateFormats.decimal(almostFifty))
        assertEquals("50°00'00.00\"N, 180°00'00.00\"E", CoordinateFormats.dms(almostFifty))
        assertEquals("50°00.0000'N, 180°00.0000'E", CoordinateFormats.ddm(almostFifty))
    }

    @Test
    fun exactDegreesAndTheAntimeridianKeepAValidHemisphere() {
        val zeroAndAntimeridian = GeoPoint(0.0, -180.0)

        assertEquals("0.000000, -180.000000", CoordinateFormats.decimal(zeroAndAntimeridian))
        assertEquals("0°00'00.00\"N, 180°00'00.00\"W", CoordinateFormats.dms(zeroAndAntimeridian))
        assertEquals("0°00.0000'N, 180°00.0000'W", CoordinateFormats.ddm(zeroAndAntimeridian))
        assertEquals("90°00'00.00\"N, 0°00'00.00\"E", CoordinateFormats.dms(GeoPoint(90.0, 0.0)))
        assertEquals("90°00'00.00\"S, 0°00'00.00\"E", CoordinateFormats.dms(GeoPoint(-90.0, 0.0)))
    }

    @Test
    fun decimalRoundsHalfUpAtTheSixthPlace() {
        assertEquals("49.821740, 24.023910", CoordinateFormats.decimal(GeoPoint(49.8217404, 24.0239096)))
    }

    @Test
    fun formatSelectsTheNotation() {
        val point = GeoPoint(49.821740, 24.023910)

        assertEquals(CoordinateFormats.decimal(point), CoordinateFormats.format(point, CoordinateFormat.DECIMAL))
        assertEquals(CoordinateFormats.dms(point), CoordinateFormats.format(point, CoordinateFormat.DMS))
        assertEquals(CoordinateFormats.ddm(point), CoordinateFormats.format(point, CoordinateFormat.DDM))
    }

    @Test
    fun theRowsAreDecimalThenDmsThenDdm() {
        val rows = CoordinateFormats.rows(GeoPoint(49.821740, 24.023910))

        assertEquals(
            listOf(
                CoordinateFormats.DECIMAL_LABEL_KEY,
                CoordinateFormats.DMS_LABEL_KEY,
                CoordinateFormats.DDM_LABEL_KEY,
            ),
            rows.map { it.labelKey },
        )
        assertEquals(
            listOf(
                "49.821740, 24.023910",
                "49°49'18.26\"N, 24°01'26.08\"E",
                "49°49.3044'N, 24°01.4346'E",
            ),
            rows.map { (it.value as PlaceCardValue.Text).text },
        )
    }

    @Test
    fun aPointThatIsNotFiniteHasNothingToDraw() {
        assertTrue(CoordinateFormats.rows(GeoPoint(Double.NaN, 1.0)).isEmpty())
        assertTrue(CoordinateFormats.rows(GeoPoint(1.0, Double.POSITIVE_INFINITY)).isEmpty())
        assertEquals("0.000000, 1.000000", CoordinateFormats.decimal(GeoPoint(Double.NaN, 1.0)))
    }
}
