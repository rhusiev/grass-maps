package nl.r1a.grassmap.map

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The distance tool's arithmetic (T-0105). The reference distances come from
 * the sphere, not from this code: one degree of latitude on the IUGG mean
 * radius is 2 * pi * 6371000 / 360 metres.
 */
class MapDistanceTest {
    @Test
    fun oneDegreeOfLatitudeIsTheSphericalArc() {
        val meters = MapDistance.meters(GeoPoint(latitude = 0.0, longitude = 0.0), GeoPoint(latitude = 1.0, longitude = 0.0))

        assertEquals(DEGREE_ARC_METERS, meters, 0.001)
    }

    @Test
    fun distanceIsSymmetric() {
        val lviv = GeoPoint(latitude = 49.8419, longitude = 24.0316)
        val kyiv = GeoPoint(latitude = 50.4501, longitude = 30.5234)

        assertEquals(MapDistance.meters(lviv, kyiv), MapDistance.meters(kyiv, lviv), 0.0)
    }

    @Test
    fun aShortDistanceIsShownInMetresWithinOnePercent() {
        val exact = MapDistance.meters(GeoPoint(0.0, 0.0), GeoPoint(latitude = 0.001, longitude = 0.0))

        val readout = MapDistance.readout(exact)

        assertEquals(DistanceUnit.Metres, readout.unit)
        assertTrue(shownWithinPercent(readout, exact, PERCENT))
    }

    @Test
    fun aLongDistanceIsShownInKilometresWithinOnePercent() {
        val exact = MapDistance.meters(GeoPoint(0.0, 0.0), GeoPoint(latitude = 0.02, longitude = 0.0))

        val readout = MapDistance.readout(exact)

        assertEquals(DistanceUnit.Kilometres, readout.unit)
        assertEquals(2.22, readout.value, 0.0)
        assertTrue(shownWithinPercent(readout, exact, PERCENT))
    }

    @Test
    fun theUnitSwitchesAtOneKilometre() {
        assertEquals(DistanceUnit.Metres, MapDistance.readout(999.9).unit)
        assertEquals(DistanceUnit.Kilometres, MapDistance.readout(1_000.0).unit)
    }

    /** The label's number, converted back to metres, against the exact value. */
    private fun shownWithinPercent(
        readout: DistanceReadout,
        exactMeters: Double,
        percent: Double,
    ): Boolean {
        val shownMeters =
            when (readout.unit) {
                DistanceUnit.Metres -> readout.value
                DistanceUnit.Kilometres -> readout.value * METRES_PER_KILOMETRE
            }
        return abs(shownMeters - exactMeters) / exactMeters <= percent
    }

    private companion object {
        const val DEGREE_ARC_METERS = 111_194.926_639
        const val METRES_PER_KILOMETRE = 1_000.0
        const val PERCENT = 0.01
    }
}
