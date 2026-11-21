package nl.r1a.grassmaps.app

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmaps.plugin.api.command.CommandResult
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugins.raster.RasterPlacement
import nl.r1a.grassmaps.plugins.raster.RasterPresets
import nl.r1a.grassmaps.plugins.raster.RasterSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The control command the raster goldens use: its refusal and its layer. */
class RasterShowCommandTest {
    private val template = "http://127.0.0.1:8765/{z}/{x}/{y}.png"

    @Test
    fun aTestBuildShowsTheFixtureAtFortyPercent() =
        runTest {
            val fixture = Fixture()

            val outcome = fixture.command.execute(params(template, "overlay", 40))

            assertTrue("$outcome", outcome is Outcome.Success)
            assertEquals(true, fixture.switch)
            val layer = fixture.settings.current()
            assertEquals(template, layer.source.tileUrlTemplate)
            assertEquals(RasterPlacement.Overlay, layer.placement)
            assertEquals(40, layer.transparencyPercent)
            assertEquals(
                "raster=$template placement=Overlay percent=40",
                value(outcome),
            )
        }

    @Test
    fun anUnderlayIsTheSameSourceUnderTheBaseMap() =
        runTest {
            val fixture = Fixture()

            val outcome = fixture.command.execute(params(template, "underlay", 40))

            assertTrue("$outcome", outcome is Outcome.Success)
            val layer = fixture.settings.current()
            assertEquals(RasterPlacement.Underlay, layer.placement)
            assertEquals(40, layer.transparencyPercent)
        }

    @Test
    fun aBuildThatIsNotDebuggableRefusesBeforeAnythingChanges() =
        runTest {
            val fixture = Fixture(debuggable = false)

            val outcome = fixture.command.execute(params(template, "overlay", 40))

            assertEquals(Outcome.Failure(AppError.PermissionDenied), outcome)
            assertNull(fixture.switch)
            assertEquals(RasterPresets.openStreetMap, fixture.settings.current().source)
            assertEquals(RasterPlacement.Overlay, fixture.settings.current().placement)
            assertEquals(100, fixture.settings.current().transparencyPercent)
        }

    @Test
    fun anInvalidTemplateNeverTurnsTheSwitchOn() =
        runTest {
            val fixture = Fixture()

            val outcome = fixture.command.execute(params("https://t.example/plain.png", "overlay", 40))

            assertEquals(Outcome.Failure(AppError.InvalidInput), outcome)
            assertNull(fixture.switch)
            assertEquals(RasterPresets.openStreetMap, fixture.settings.current().source)
        }

    @Test
    fun theFixtureSourceCarriesTheZoomRangeItServes() =
        runTest {
            val fixture = Fixture()

            val outcome =
                fixture.command.execute(
                    JsonObject(
                        mapOf(
                            "tiles" to JsonPrimitive(template),
                            "minZoom" to JsonPrimitive(12),
                            "maxZoom" to JsonPrimitive(13),
                        ),
                    ),
                )

            assertTrue("$outcome", outcome is Outcome.Success)
            val source = fixture.settings.current().source
            assertEquals(12, source.minZoom)
            assertEquals(13, source.maxZoom)
            // The probe asks for the lowest zoom, so it lands on a served tile.
            assertEquals(12, fixture.probed?.minZoom)
        }

    @Test
    fun aZoomRangeTheSourceCannotCarryIsRefusedBeforeTheSwitch() =
        runTest {
            val fixture = Fixture()

            val outcome =
                fixture.command.execute(
                    JsonObject(
                        mapOf(
                            "tiles" to JsonPrimitive(template),
                            "minZoom" to JsonPrimitive(13),
                            "maxZoom" to JsonPrimitive(12),
                        ),
                    ),
                )

            assertEquals(Outcome.Failure(AppError.InvalidInput), outcome)
            assertNull(fixture.switch)
        }

    @Test
    fun anUnknownPlacementIsInvalidInput() =
        runTest {
            val fixture = Fixture()

            val outcome = fixture.command.execute(params(template, "sideways", 40))

            assertEquals(Outcome.Failure(AppError.InvalidInput), outcome)
            assertNull(fixture.switch)
        }

    @Test
    fun aFailedProbeLeavesThePreviousSource() =
        runTest {
            val fixture = Fixture(probe = { Outcome.Failure(AppError.Network) })

            val outcome = fixture.command.execute(params(template, "overlay", 40))

            assertEquals(Outcome.Failure(AppError.Network), outcome)
            assertEquals(RasterPresets.openStreetMap, fixture.settings.current().source)
        }

    @Test
    fun aNullTilesClearsTheLayerAndTurnsTheSwitchOff() =
        runTest {
            val fixture = Fixture()
            fixture.command.execute(params(template, "overlay", 40))

            val outcome = fixture.command.execute(JsonObject(mapOf("tiles" to JsonNull)))

            assertTrue("$outcome", outcome is Outcome.Success)
            assertEquals(false, fixture.switch)
            assertEquals("raster=off", value(outcome))
        }

    @Test
    fun aSecondCallWithTheSameTemplateKeepsTheActiveSource() =
        runTest {
            var probes = 0
            val fixture =
                Fixture(probe = {
                    probes += 1
                    Outcome.Success(Unit)
                })

            fixture.command.execute(params(template, "overlay", 100))
            fixture.command.execute(params(template, "underlay", 40))

            assertEquals(1, probes)
            assertEquals(RasterPlacement.Underlay, fixture.settings.current().placement)
            assertEquals(40, fixture.settings.current().transparencyPercent)
        }

    private fun params(
        tiles: String?,
        placement: String?,
        percent: Int?,
    ): JsonElement {
        val fields = mutableMapOf<String, JsonElement>()
        if (tiles != null) {
            fields["tiles"] = JsonPrimitive(tiles)
        }
        if (placement != null) {
            fields["placement"] = JsonPrimitive(placement)
        }
        if (percent != null) {
            fields["percent"] = JsonPrimitive(percent)
        }
        return JsonObject(fields)
    }

    private fun value(outcome: Outcome<CommandResult<String>>): String =
        when (outcome) {
            is Outcome.Success -> outcome.value.value
            is Outcome.Failure -> throw AssertionError("expected a result, got ${outcome.error}")
        }

    private class Fixture(
        debuggable: Boolean = true,
        private val probe: suspend (RasterSource) -> Outcome<Unit> = { Outcome.Success(Unit) },
    ) {
        val settings = RasterLayerSettings(file = null)
        var switch: Boolean? = null
        var probed: RasterSource? = null
        val command =
            RasterShowCommand(
                settings = { settings },
                enableOnlineTiles = { enabled ->
                    switch = enabled
                    Outcome.Success(Unit)
                },
                probe = { source ->
                    probed = source
                    probe(source)
                },
                debuggable = { debuggable },
                log = {},
            )
    }
}
