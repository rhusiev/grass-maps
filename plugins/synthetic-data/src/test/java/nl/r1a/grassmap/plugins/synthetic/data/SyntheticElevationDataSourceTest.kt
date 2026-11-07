package nl.r1a.grassmap.plugins.synthetic.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.contract.CursorToken
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerType
import nl.r1a.grassmap.plugin.api.model.MapSourceIds
import nl.r1a.grassmap.plugin.api.provider.AttributePage
import nl.r1a.grassmap.plugin.api.provider.AttributeRequest
import nl.r1a.grassmap.plugin.api.provider.CostModifier
import nl.r1a.grassmap.plugin.api.provider.CostModifierRequest
import nl.r1a.grassmap.plugin.api.provider.CostModifierRules
import nl.r1a.grassmap.plugin.api.provider.TravelDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticElevationDataSourceTest {
    private val source = SyntheticElevationDataSource()

    @Test
    fun descriptionNamesTheSyntheticElevationFixture() {
        val description = source.describe()

        assertEquals(setOf("elevation"), description.attributeKinds)
        assertEquals(listOf("fixture-mini"), description.regions)
        assertEquals("static", description.refreshPolicy)
    }

    @Test
    fun requestOutsideTheFixtureAreaIsAnEmptyPage() =
        runTest {
            val outside =
                BoundingBox(
                    southWest = GeoPoint(48.0, 22.0),
                    northEast = GeoPoint(48.1, 22.1),
                )

            assertEquals(
                Outcome.Success(AttributePage(emptyList(), null)),
                source.attributes(AttributeRequest(outside, TIME_MILLIS, limit = 2), CancellationHandle.None),
            )
        }

    @Test
    fun aCursorAtTheEndIsAnEmptyPageAndOnePastItIsRejected() =
        runTest {
            val end = CursorToken("${SyntheticElevationDataSource.CURSOR_PREFIX}:5")
            val pastEnd = CursorToken("${SyntheticElevationDataSource.CURSOR_PREFIX}:6")

            assertEquals(
                Outcome.Success(AttributePage(emptyList(), null)),
                source.attributes(request(limit = 2, cursor = end), CancellationHandle.None),
            )
            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                source.attributes(request(limit = 2, cursor = pastEnd), CancellationHandle.None),
            )
        }

    @Test
    fun anUnknownCursorIsInvalidInput() =
        runTest {
            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                source.attributes(
                    request(limit = 2, cursor = CursorToken("another-query:0")),
                    CancellationHandle.None,
                ),
            )
        }

    @Test
    fun cancelledCallsDoNoWork() =
        runTest {
            val cancelled = CancellationHandle { true }

            assertEquals(
                Outcome.Failure(AppError.Cancelled),
                source.attributes(request(limit = 2), cancelled),
            )
            assertEquals(
                Outcome.Failure(AppError.Cancelled),
                source.costModifiers(CostModifierRequest(ROUTE), cancelled),
            )
        }

    @Test
    fun costModifiersCarryExactlyOneEffectPerWay() =
        runTest {
            val first = source.costModifiers(CostModifierRequest(ROUTE), CancellationHandle.None)
            val second = source.costModifiers(CostModifierRequest(ROUTE), CancellationHandle.None)

            assertEquals(first, second)
            val modifiers = success(first)
            assertTrue(modifiers.isNotEmpty())
            for (modifier in modifiers) {
                assertNull(CostModifierRules.violationOf(modifier))
                assertEquals(1, effectCount(modifier))
                assertTrue(modifier.geometry.size >= 2)
                assertTrue(modifier.geometry.all { SyntheticFixture.bounds.contains(it) })
                assertEquals(SyntheticFixture.REGION_BUILD, modifier.regionBuild)
            }
            assertEquals(
                setOf(TravelDirection.Forward, TravelDirection.Both),
                modifiers.map { it.direction }.toSet(),
            )
            assertEquals(1.2, modifiers.first { it.multiplier != null }.multiplier ?: 0.0, 0.0)
            assertEquals(45.0, modifiers.first { it.addedSeconds != null }.addedSeconds ?: 0.0, 0.0)
        }

    @Test
    fun staleBuildAndFarAwayRouteProduceNoModifiers() =
        runTest {
            assertEquals(
                Outcome.Success(emptyList<CostModifier>()),
                source.costModifiers(
                    CostModifierRequest(ROUTE, regionBuild = "fixture-mini-2025-01-01-1"),
                    CancellationHandle.None,
                ),
            )
            assertEquals(
                Outcome.Success(emptyList<CostModifier>()),
                source.costModifiers(
                    CostModifierRequest(listOf(GeoPoint(48.0, 22.0), GeoPoint(48.1, 22.1))),
                    CancellationHandle.None,
                ),
            )
        }

    @Test
    fun aRouteWithFewerThanTwoWaypointsIsInvalidInput() =
        runTest {
            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                source.costModifiers(
                    CostModifierRequest(listOf(GeoPoint(49.941, 23.941))),
                    CancellationHandle.None,
                ),
            )
        }

    @Test
    fun mapLayerIsARealContourOverlay() {
        val layer = source.mapLayer()

        assertEquals(LayerSlot.Overlays, layer.slot)
        assertEquals(LayerType.Line, layer.spec.type)
        assertEquals(MapSourceIds.CONTOURS, layer.spec.source)
        assertEquals("contours", layer.spec.sourceLayer)
        assertEquals(
            JsonArray(listOf(JsonPrimitive("=="), JsonPrimitive("idx"), JsonPrimitive(true))),
            layer.spec.filter,
        )
    }

    private fun request(
        limit: Int,
        cursor: CursorToken? = null,
    ): AttributeRequest = AttributeRequest(SyntheticFixture.bounds, TIME_MILLIS, limit, cursor)

    private fun effectCount(modifier: CostModifier): Int =
        listOf(modifier.multiplier != null, modifier.addedSeconds != null, modifier.forbidden)
            .count { it }

    private fun success(outcome: Outcome<List<CostModifier>>): List<CostModifier> =
        when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("expected modifiers, got ${outcome.error}")
        }

    private companion object {
        const val TIME_MILLIS = 1_792_000_000_000L
        val ROUTE =
            listOf(
                GeoPoint(49.9410, 23.9410),
                GeoPoint(49.9450, 23.9490),
            )
    }
}
