package nl.r1a.grassmap.plugin.api.testing

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.CostModifier
import nl.r1a.grassmap.plugin.api.provider.CostModifierRules
import nl.r1a.grassmap.plugin.api.provider.DataSourceContract
import nl.r1a.grassmap.plugin.api.provider.TravelDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DataSourceContractTest {
    @Test
    fun dataSourceContractIsNamedAndVersioned() {
        assertEquals("data.source", DataSourceContract.NAME)
        assertEquals("1.0.0", DataSourceContract.VERSION)
        assertEquals(DataSourceContract.NAME, DataSourceContract.REFERENCE.name)
        assertEquals(DataSourceContract.VERSION, DataSourceContract.REFERENCE.version)
    }

    @Test
    fun eachSingleEffectIsWellFormed() {
        assertNull(CostModifierRules.violationOf(modifier(multiplier = 1.2)))
        assertNull(CostModifierRules.violationOf(modifier(addedSeconds = 45.0)))
        assertNull(CostModifierRules.violationOf(modifier(forbidden = true)))
    }

    @Test
    fun noEffectOrTwoEffectsIsRejected() {
        assertEquals(AppError.InvalidInput, CostModifierRules.violationOf(modifier()))
        assertEquals(
            AppError.InvalidInput,
            CostModifierRules.violationOf(modifier(multiplier = 1.2, addedSeconds = 10.0)),
        )
        assertEquals(
            AppError.InvalidInput,
            CostModifierRules.violationOf(modifier(multiplier = 1.2, forbidden = true)),
        )
    }

    @Test
    fun malformedGeometryAndBuildAreRejected() {
        assertEquals(
            AppError.InvalidInput,
            CostModifierRules.violationOf(modifier(multiplier = 1.2, geometry = emptyList())),
        )
        assertEquals(
            AppError.InvalidInput,
            CostModifierRules.violationOf(modifier(multiplier = 1.2, regionBuild = " ")),
        )
        assertEquals(
            AppError.InvalidInput,
            CostModifierRules.violationOf(modifier(multiplier = 1.2, osmWayId = 0)),
        )
    }

    @Test
    fun nonPositiveEffectsAreRejected() {
        assertEquals(AppError.InvalidInput, CostModifierRules.violationOf(modifier(multiplier = 0.0)))
        assertEquals(AppError.InvalidInput, CostModifierRules.violationOf(modifier(multiplier = -1.0)))
        assertEquals(AppError.InvalidInput, CostModifierRules.violationOf(modifier(addedSeconds = -1.0)))
        assertEquals(
            AppError.InvalidInput,
            CostModifierRules.violationOf(modifier(multiplier = Double.NaN)),
        )
    }

    private fun modifier(
        osmWayId: Long = 1,
        geometry: List<GeoPoint> = LINE,
        regionBuild: String = "fixture-mini",
        multiplier: Double? = null,
        addedSeconds: Double? = null,
        forbidden: Boolean = false,
    ): CostModifier =
        CostModifier(
            osmWayId = osmWayId,
            geometry = geometry,
            direction = TravelDirection.Forward,
            regionBuild = regionBuild,
            multiplier = multiplier,
            addedSeconds = addedSeconds,
            forbidden = forbidden,
        )

    private companion object {
        val LINE = listOf(GeoPoint(49.94, 23.94), GeoPoint(49.95, 23.95))
    }
}
