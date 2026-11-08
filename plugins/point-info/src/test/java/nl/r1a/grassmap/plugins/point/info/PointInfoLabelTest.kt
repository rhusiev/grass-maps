package nl.r1a.grassmap.plugins.point.info

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * The two label rows the map shows (T-0112). The values are the ones the
 * plugin publishes, and the strings are the ones the app resources supply.
 */
class PointInfoLabelTest {
    private val strings = TestStrings()
    private lateinit var deviceLocale: Locale

    @Before
    fun pinTheDecimalSeparator() {
        deviceLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreTheDeviceLocale() {
        Locale.setDefault(deviceLocale)
    }

    @Test
    fun theRowsShowTheDistanceInMetresAndTheAzimuthInDegrees() {
        val rows =
            PointInfoLabel.rows(
                SelectedPointValue(
                    point = GeoPoint(latitude = 49.8500, longitude = 24.0400),
                    distanceMeters = 1_084.755_494,
                    azimuthDegrees = 33.842_680,
                ),
                strings,
            )

        assertEquals(listOf("Distance 1.08 km", "Azimuth 33.8°"), rows)
    }

    @Test
    fun aDistanceUnderAKilometreIsShownInMetres() {
        val rows =
            PointInfoLabel.rows(
                SelectedPointValue(
                    point = GeoPoint(latitude = 49.8419, longitude = 24.0317),
                    distanceMeters = 0.14,
                    azimuthDegrees = 90.0,
                ),
                strings,
            )

        assertEquals("Distance 0.1 m", rows.first())
    }

    @Test
    fun anAzimuthIsNormalisedIntoTheFullCircle() {
        val rows =
            PointInfoLabel.rows(
                SelectedPointValue(
                    point = GeoPoint(latitude = 0.0, longitude = 0.0),
                    distanceMeters = 1_000.0,
                    azimuthDegrees = -129.596_697,
                ),
                strings,
            )

        assertEquals("Azimuth 230.4°", rows.last())
    }

    @Test
    fun noSelectionMeansNoRows() {
        assertTrue(PointInfoLabel.rows(SelectedPointValue(), strings).isEmpty())
    }

    @Test
    fun aSelectionWithoutAnOriginMeansNoRows() {
        val value = SelectedPointValue(point = GeoPoint(latitude = 49.85, longitude = 24.04))

        assertTrue(PointInfoLabel.rows(value, strings).isEmpty())
    }

    @Test
    fun theRowKeysAreTheOnesTheAppResourcesDeclare() {
        assertEquals("point_info_distance_row", PointInfoLabel.DISTANCE_ROW_KEY)
        assertEquals("point_info_azimuth_row", PointInfoLabel.AZIMUTH_ROW_KEY)
    }
}
