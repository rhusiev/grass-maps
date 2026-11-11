package nl.r1a.grassmap.plugin.api.widget

import kotlinx.serialization.json.Json
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetContractTest {
    @Test
    fun theCadenceBoundsKeepWidgetsAtOrBelowTenHertz() {
        assertTrue(WidgetCadence.MIN_INTERVAL_MILLIS > 0)
        assertTrue(WidgetCadence.DEFAULT_INTERVAL_MILLIS >= WidgetCadence.MIN_INTERVAL_MILLIS)
        assertTrue(1_000L / WidgetCadence.MIN_INTERVAL_MILLIS <= MAX_HERTZ)
    }

    @Test
    fun anAltitudeValueRoundTripsThroughItsSerializer() {
        val value = AltitudeWidgetValue(352.75, AltitudeSource.Dem)

        val encoded = Json.encodeToJsonElement(AltitudeWidgetValue.serializer(), value)
        val decoded = Json.decodeFromJsonElement(AltitudeWidgetValue.serializer(), encoded)

        assertEquals(value, decoded)
    }

    @Test
    fun coordinatesCarryTheFixAndTheCentre() {
        val value =
            CoordinatesWidgetValue(
                fix = GeoPoint(49.9490, 23.9570),
                mapCenter = GeoPoint(49.8400, 24.0300),
            )

        val encoded = Json.encodeToJsonElement(CoordinatesWidgetValue.serializer(), value)
        val decoded = Json.decodeFromJsonElement(CoordinatesWidgetValue.serializer(), encoded)

        assertEquals(value, decoded)
    }

    @Test
    fun gpsInfoCarriesTheFixFields() {
        val value = GpsInfoWidgetValue(3.5, 1.4, 271.0, 1_792_000_123_000L)

        val encoded = Json.encodeToJsonElement(GpsInfoWidgetValue.serializer(), value)
        val decoded = Json.decodeFromJsonElement(GpsInfoWidgetValue.serializer(), encoded)

        assertEquals(value, decoded)
    }

    @Test
    fun aSpeedValueRoundTripsMeasuredAndPending() {
        listOf(SpeedWidgetValue(1.3888888888888888), SpeedWidgetValue(null)).forEach { value ->
            val encoded = Json.encodeToJsonElement(SpeedWidgetValue.serializer(), value)
            val decoded = Json.decodeFromJsonElement(SpeedWidgetValue.serializer(), encoded)

            assertEquals(value, decoded)
        }
    }

    private companion object {
        const val MAX_HERTZ = 10
    }
}
